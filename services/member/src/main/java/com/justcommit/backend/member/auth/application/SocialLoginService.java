package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.infrastructure.jwt.JwtProvider;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.lock.MemberLocker;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.unlink.domain.KakaoUnlinkReceipt;
import com.justcommit.backend.member.unlink.infrastructure.repository.KakaoUnlinkReceiptRepository;
import com.justcommit.backend.member.withdrawal.application.RejoinRestriction;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;

// 소셜 로그인: 기존 회원이면 토큰 발급, 신규 회원이면 가입 토큰 발급
@Service
@RequiredArgsConstructor
public class SocialLoginService {

  private final MemberRepository memberRepository;
  private final MemberLocker memberLocker;
  private final JwtProvider jwtProvider;
  private final RefreshTokenIssuer refreshTokenIssuer;
  private final AccessIssueTimeGuard accessIssueTimeGuard;
  private final KakaoUnlinkReceiptRepository unlinkReceiptRepository;
  private final RejoinRestriction rejoinRestriction;

  @Value("${jwt.access-token-expiration}")
  private Duration accessTokenExpiration;

  @Value("${jwt.signup-token-expiration}")
  private Duration signupTokenExpiration;

  // 트랜잭션은 AccessIssueTimeGuard가 연다 (#546): 폐기와 같은 초면 롤백하고(회원 잠금 해제) 다음 초까지 기다린 뒤
  // 아래 처리를 처음부터 다시 실행한다. 그래서 이 메서드에는 @Transactional을 붙이지 않는다
  public SocialLoginResult login(SocialLoginCommand command) {
    return accessIssueTimeGuard.runWithRetry(() -> loginInTransaction(command));
  }

  // 비밀번호 변경 · 탈퇴(같은 회원 행 잠금)와 겹치면 한쪽이 끝날 때까지 기다리고, 잠근 뒤 읽은 값으로 판단 (#409)
  private SocialLoginResult loginInTransaction(SocialLoginCommand command) {
    // 기존 소셜 회원은 회원번호로 찾고 회원 행을 잠금 (카카오 이메일은 사용자가 바꿀 수 있지만 회원번호는 바뀌지 않음)
    // 행이 없을 때(신규)는 잠글 대상이 없음 → 동시 가입은 UNIQUE 제약 · 가입 처리가 막는다
    Member member = memberLocker
        .lockByProvider(command.provider(), command.providerId())
        .orElse(null);
    if (member != null) {
      // 탈퇴 회원은 토큰을 발급하지 않음 (탈퇴 · 재가입 안내는 #407)
      if (member.getStatus() == MemberStatus.DELETED) {
        throw new BusinessException(MemberErrorCode.SOCIAL_LOGIN_FAILED);
      }
      // 발급 시각 확인을 아래 상태 변경보다 먼저 (#546): 최근 폐기 시각과 같은 초면 여기서 중단되고,
      // 연결 해제 기록 무효화 · ACTIVE 복구는 발급할 수 있는 시도에서만 실행된다
      Instant issuedAt = accessIssueTimeGuard.issuableNow(member.getId());
      // 카카오 재로그인 = 재연결 (#422). 같은 회원 잠금 안에서 처리:
      // - 이전 미처리 연결 해제 기록은 항상(ACTIVE여도) 무효. 작업자가 끊김을 확인한 직후 다시 로그인한 경우,
      //   옛 조회 결과로 방금 돌아온 회원을 INACTIVE로 바꾸지 않게 한다 (대기 중인 안내 메일도 취소)
      // - INACTIVE면 ACTIVE로 복구 (관리자 해제로 INACTIVE가 된 회원 포함, 이용 정지로 취급하지 않음)
      // - 탈퇴가 보류됐던 복구인지는 무효화가 단계를 지우기 전에 확인한다 (#616)
      boolean withdrawalHeldRestored = false;
      if (command.provider() == Provider.KAKAO) {
        withdrawalHeldRestored = member.getStatus() == MemberStatus.INACTIVE && wasWithdrawalHeld(member);
        unlinkReceiptRepository.invalidatePending(command.providerId(), LocalDateTime.now());
      }
      if (member.getStatus() == MemberStatus.INACTIVE) {
        member.restoreActive();
      }
      return new SocialLoginResult.ExistingMember(issueTokens(member, issuedAt), withdrawalHeldRestored);
    }

    // 신규: 같은 이메일로 가입한 회원이 있으면 소셜 가입 차단
    String email = normalizeEmail(command.email());
    if (memberRepository.existsByEmail(email)) {
      throw new BusinessException(MemberErrorCode.SOCIAL_EMAIL_CONFLICT);
    }
    // 30일 안에 탈퇴한 이메일이면 가입 토큰을 주지 않고 추가정보 입력 전에 안내 (#611)
    // 성공 핸들러가 /oauth/callback?error=REJOIN_RESTRICTED_EMAIL로 보낸다. 최종 판정은 소셜 가입 트랜잭션에서 다시 한다
    rejoinRestriction.checkEmail(email);

    String signupToken = jwtProvider.createSignupToken(
            command.provider().name(), command.providerId(), email);
    return new SocialLoginResult.NewMember(signupToken, signupTokenExpiration.toSeconds());
  }

  // 지금 연결 해제 상태(INACTIVE로 바꿀 때 저장한 인증 버전 = 지금 회원의 인증 버전)의 자동 탈퇴 처리 기록이
  // 탈퇴 보류로 정해졌는지 (#616). 처리기(KakaoUnlinkProcessor)와 같은 기준으로 같은 연결 해제 상태를 찾는다.
  // 관리자 해제 · 자동 탈퇴 대상이 아닌 사유는 처리 기록이 없고, 판정 전이면 보류 단계가 아니라서 false
  // 기록을 잠그고 읽는다 (회원 잠금 다음, 처리기와 같은 회원 → 기록 순서): 읽은 뒤 무효화하기 전에 처리기가
  // BLOCKED를 저장하면 안내 없이 보류가 무효화되므로, 처리기의 결과 반영은 이 로그인이 끝날 때까지 기다리게 한다.
  // 처리기가 먼저 잠갔으면 그 반영이 끝난 뒤의 상태를 읽는다. 이미 시작된 안내 메일 발송은 잠금 밖이라 그대로
  private boolean wasWithdrawalHeld(Member member) {
    return unlinkReceiptRepository
        .findWithdrawalHandlerForUpdate(member.getId(), member.getTokenVersion())
        .map(KakaoUnlinkReceipt::isWithdrawalHeld)
        .orElse(false);
  }

  // LoginService와 같은 방식으로 Access·Refresh 발급 (기존 회원: 잠금 · 상태 · 발급 시각 확인 뒤 호출됨)
  private LoginResult issueTokens(Member member, Instant issuedAt) {
    String role = member.getRole().name();
    String accessToken = jwtProvider.createAccessToken(member.getId(), role, issuedAt);
    String refreshToken = refreshTokenIssuer.issue(member.getId(), member.getTokenVersion());

    return new LoginResult(
            member.getId(),
            accessToken,
            accessTokenExpiration.toSeconds(),
            member.getNickname(),
            role,
            refreshToken,
            RefreshTokenIssuer.REFRESH_TOKEN_TTL
    );
  }

  // LoginService·SignupService와 같은 규칙
  private String normalizeEmail(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }
}

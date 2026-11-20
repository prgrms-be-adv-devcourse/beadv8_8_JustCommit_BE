package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.infrastructure.jwt.JwtProvider;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.lock.MemberLocker;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import com.justcommit.backend.member.unlink.infrastructure.repository.KakaoUnlinkReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
  private final DatabaseClock databaseClock;
  private final KakaoUnlinkReceiptRepository unlinkReceiptRepository;

  @Value("${jwt.access-token-expiration}")
  private Duration accessTokenExpiration;

  @Value("${jwt.signup-token-expiration}")
  private Duration signupTokenExpiration;

  // 비밀번호 변경 · 탈퇴(같은 회원 행 잠금)와 겹치면 한쪽이 끝날 때까지 기다리고, 잠근 뒤 읽은 값으로 판단 (#409)
  @Transactional
  public SocialLoginResult login(SocialLoginCommand command) {
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
      // 카카오 재로그인 = 재연결 (#422). 같은 회원 잠금 안에서 처리:
      // - 이전 미처리 연결 해제 기록은 항상(ACTIVE여도) 무효. 작업자가 끊김을 확인한 직후 다시 로그인한 경우,
      //   옛 조회 결과로 방금 돌아온 회원을 INACTIVE로 바꾸지 않게 한다 (대기 중인 안내 메일도 취소)
      // - INACTIVE면 ACTIVE로 복구 (관리자 해제로 INACTIVE가 된 회원 포함, 이용 정지로 취급하지 않음)
      if (command.provider() == Provider.KAKAO) {
        unlinkReceiptRepository.invalidatePending(command.providerId(), LocalDateTime.now());
      }
      if (member.getStatus() == MemberStatus.INACTIVE) {
        member.restoreActive();
      }
      return new SocialLoginResult.ExistingMember(issueTokens(member));
    }

    // 신규: 같은 이메일로 가입한 회원이 있으면 소셜 가입 차단
    String email = normalizeEmail(command.email());
    if (memberRepository.existsByEmail(email)) {
      throw new BusinessException(MemberErrorCode.SOCIAL_EMAIL_CONFLICT);
    }

    String signupToken = jwtProvider.createSignupToken(
            command.provider().name(), command.providerId(), email);
    return new SocialLoginResult.NewMember(signupToken, signupTokenExpiration.toSeconds());
  }

  // LoginService와 같은 방식으로 Access·Refresh 발급 (기존 회원: 잠금 · 상태 확인 뒤 호출됨)
  private LoginResult issueTokens(Member member) {
    // 회원 잠금과 검사를 마친 뒤 DB 시각을 읽어 Access 발급 시각으로 (#409)
    Instant issuedAt = databaseClock.now();
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

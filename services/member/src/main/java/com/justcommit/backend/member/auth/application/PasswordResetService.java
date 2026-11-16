package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.domain.RevocationReason;
import com.justcommit.backend.member.auth.infrastructure.redis.PasswordResetTokenStore;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * 비밀번호 재설정 (#412). 재설정 목적 이메일 인증에 성공하면 일회용 토큰을 발급하고,
 * 그 토큰으로 새 비밀번호를 저장하며 모든 기기에서 로그아웃시킨다 (#409 토큰 폐기).
 * - 재설정 대상: 활동 중(ACTIVE)이고 비밀번호가 있는(로컬) 회원. 소셜 회원 · 탈퇴 회원은 대상이 아니다
 * - 토큰: 32바이트 난수(Base64url). Redis에 해시로, 회원 id · 발급 당시 인증 버전과 함께 30분 저장
 * - 재설정: 토큰을 꺼내면서 삭제(한 번만) → 새 비밀번호 해시(잠금 밖) → revoke() 잠금 안에서
 *   회원 상태 · 로컬 계정 · 인증 버전을 다시 확인하고 저장. 틀린 토큰에는 BCrypt를 돌리지 않는다
 * - 토큰을 꺼낸 뒤 실패해도(잠금 대기 409 · 결과 불명 503) 되살리지 않는다 (같은 토큰이 두 번 쓰일 틈을 만들지 않음).
 *   사용자는 이메일 인증을 다시 받으면 된다
 * - 트랜잭션을 열지 않는다: revoke()는 트랜잭션 밖에서 호출해야 커밋 결과를 판단할 수 있다
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

  static final Duration RESET_TOKEN_TTL = Duration.ofMinutes(30);
  private static final int RESET_TOKEN_BYTES = 32; // 256비트 랜덤
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();
  private static final String RESULT_UNKNOWN_MESSAGE =
      "비밀번호 재설정 결과를 확인하지 못했습니다. 새 비밀번호로 로그인해 확인해 주세요.";

  private final MemberRepository memberRepository;
  private final PasswordResetTokenStore tokenStore;
  private final TokenRevocationService tokenRevocationService;
  private final PasswordEncoder passwordEncoder;

  // 재설정할 수 있는 회원인지 (재설정 인증번호를 실제로 보낼지 정할 때)
  public boolean canReset(String email) {
    return memberRepository.findByEmail(email).filter(PasswordResetService::resettable).isPresent();
  }

  // 재설정 목적 이메일 인증에 성공한 뒤 호출. 재설정할 수 있는 회원이면 토큰을 만들어 저장하고 돌려준다
  public Optional<String> issueToken(String email) {
    return memberRepository.findByEmail(email)
        .filter(PasswordResetService::resettable)
        .map(member -> {
          String token = newToken();
          tokenStore.save(token, member.getId(), member.getTokenVersion(), RESET_TOKEN_TTL);
          return token;
        });
  }

  public PasswordResetResult reset(String resetToken, String newPassword) {
    PasswordResetTokenStore.ResetTokenOwner owner = tokenStore.take(resetToken)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.INVALID_RESET_TOKEN));
    // 새 비밀번호 해시는 회원 잠금 전에 만든다 (잠금 시간 단축, 비밀번호 변경과 같은 방식)
    String encodedNewPassword = passwordEncoder.encode(newPassword);
    RevocationResult result;
    try {
      result = tokenRevocationService.revoke(owner.memberId(), RevocationReason.PASSWORD_RESET, member -> {
        // 토큰을 받은 뒤 탈퇴했거나, 비밀번호가 바뀌었다면(인증 버전이 다름) 이 토큰으로는 재설정할 수 없다
        if (!resettable(member) || member.getTokenVersion() != owner.tokenVersion()) {
          throw new BusinessException(MemberErrorCode.INVALID_RESET_TOKEN);
        }
        member.changePassword(encodedNewPassword);
      });
    } catch (RevocationOutcomeUnknownException e) {
      throw new BusinessException(MemberErrorCode.RESULT_UNKNOWN, RESULT_UNKNOWN_MESSAGE);
    }
    return new PasswordResetResult(result == RevocationResult.COMPLETED);
  }

  private static boolean resettable(Member member) {
    return member.getStatus() == MemberStatus.ACTIVE && member.getPassword() != null;
  }

  private static String newToken() {
    byte[] bytes = new byte[RESET_TOKEN_BYTES];
    SECURE_RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}

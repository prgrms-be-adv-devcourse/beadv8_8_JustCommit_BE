package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.infrastructure.jwt.JwtProvider;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.lock.MemberLocker;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

// 이메일·비밀번호 로그인
@Service
@RequiredArgsConstructor
public class LoginService {

  private final MemberLocker memberLocker;
  private final PasswordEncoder passwordEncoder;
  private final JwtProvider jwtProvider;
  private final RefreshTokenIssuer refreshTokenIssuer;
  private final AccessIssueTimeGuard accessIssueTimeGuard;

  // Access 토큰 유효 시간
  // application.yml의 jwt.access-token-expiration: 30m → Duration으로 자동 변환
  @Value("${jwt.access-token-expiration}")
  private Duration accessTokenExpiration;

  // 트랜잭션은 AccessIssueTimeGuard가 연다 (#546): 폐기와 같은 초면 롤백하고(회원 잠금 해제) 다음 초까지 기다린 뒤
  // 아래 처리를 처음부터 다시 실행한다. 그래서 이 메서드에는 @Transactional을 붙이지 않는다
  public LoginResult login(LoginCommand command) {
    String email = normalizeEmail(command.email());
    return accessIssueTimeGuard.runWithRetry(() -> loginInTransaction(email, command.password()));
  }

  // 로그인: 회원 행 잠금 조회 → 탈퇴 회원 · 비밀번호 확인 → 발급 시각 확인 → Access 발급 → Refresh 발급 · Redis 저장 → 커밋
  // 비밀번호 변경 · 탈퇴(같은 회원 행 잠금)와 겹치면 한쪽이 끝날 때까지 기다리고, 잠근 뒤 읽은 값으로 판단 (#409)
  private LoginResult loginInTransaction(String email, String password) {
    // 없는 이메일 · 탈퇴 회원 · 비밀번호 불일치를 같은 에러로 처리 (탈퇴 여부를 드러내지 않음)
    Member member = memberLocker.lockByEmail(email)
        .filter(found -> found.getStatus() != MemberStatus.DELETED)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.LOGIN_FAILED));
    if (!passwordEncoder.matches(password, member.getPassword())) {
      throw new BusinessException(MemberErrorCode.LOGIN_FAILED);
    }

    // 회원 잠금과 검사를 마친 뒤 DB 시각을 읽어 Access 발급 시각으로 (#409)
    // 최근 폐기 시각과 같은 초면 여기서 중단되고, Refresh는 아직 저장하지 않은 상태다 (#546)
    Instant issuedAt = accessIssueTimeGuard.issuableNow(member.getId());
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

  // SignupService·EmailVerificationService와 같은 규칙(가입 때 저장한 값과 일치해야 함)
  private String normalizeEmail(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }
}

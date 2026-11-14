package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.infrastructure.jwt.JwtProvider;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

// 이메일·비밀번호 로그인
@Service
@RequiredArgsConstructor
public class LoginService {

  private final MemberRepository memberRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtProvider jwtProvider;
  private final RefreshTokenIssuer refreshTokenIssuer;
  private final DatabaseClock databaseClock;

  // Access 토큰 유효 시간
  // application.yml의 jwt.access-token-expiration: 30m → Duration으로 자동 변환
  @Value("${jwt.access-token-expiration}")
  private Duration accessTokenExpiration;

  // 로그인: 회원 행 잠금 조회 → 탈퇴 회원 · 비밀번호 확인 → Access 발급 → Refresh 발급 · Redis 저장 → 커밋
  // 비밀번호 변경 · 탈퇴(같은 회원 행 잠금)와 겹치면 한쪽이 끝날 때까지 기다리고, 잠근 뒤 읽은 값으로 판단 (#409)
  @Transactional
  public LoginResult login(LoginCommand command) {
    String email = normalizeEmail(command.email());

    // 없는 이메일 · 탈퇴 회원 · 비밀번호 불일치를 같은 에러로 처리 (탈퇴 여부를 드러내지 않음)
    Member member = memberRepository.findByEmailForUpdate(email)
        .filter(found -> found.getStatus() != MemberStatus.DELETED)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.LOGIN_FAILED));
    if (!passwordEncoder.matches(command.password(), member.getPassword())) {
      throw new BusinessException(MemberErrorCode.LOGIN_FAILED);
    }

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

  // SignupService·EmailVerificationService와 같은 규칙(가입 때 저장한 값과 일치해야 함)
  private String normalizeEmail(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }
}

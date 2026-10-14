package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.security.JwtProvider;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.Role;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Locale;

// 이메일·비밀번호 로그인
@Service
@RequiredArgsConstructor
public class LoginService {

  private final MemberRepository memberRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtProvider jwtProvider;
  private final RefreshTokenIssuer refreshTokenIssuer;

  // Access 토큰 유효 시간
  // application.yml의 jwt.access-token-expiration: 30m → Duration으로 자동 변환
  @Value("${jwt.access-token-expiration}")
  private Duration accessTokenExpiration;

  // 로그인: 회원 조회 → 시스템 계정 차단 → 비밀번호 확인 → Access 발급 → Refresh 발급·Redis 저장
  @Transactional(readOnly = true)
  public LoginResult login(LoginCommand command) {
    String email = normalizeEmail(command.email());

    // 이메일 없음과 비밀번호 불일치를 같은 에러로 처리
    Member member = memberRepository.findByEmail(email)
            .orElseThrow(() -> new BusinessException(MemberErrorCode.LOGIN_FAILED));
    // 시스템 계정은 서버 내부용이라 로그인 불가
    if (member.getRole() == Role.SYSTEM) {
      throw new BusinessException(MemberErrorCode.LOGIN_FAILED);
    }
    if (!passwordEncoder.matches(command.password(), member.getPassword())) {
      throw new BusinessException(MemberErrorCode.LOGIN_FAILED);
    }

    String role = member.getRole().name();
    String accessToken = jwtProvider.createAccessToken(member.getId(), role);
    String refreshToken = refreshTokenIssuer.issue(member.getId());

    return new LoginResult(
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

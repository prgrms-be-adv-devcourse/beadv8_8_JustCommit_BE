package com.justcommit.backend.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.security.JwtProvider;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.redis.RefreshTokenRedisStore;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

// 이메일·비밀번호 로그인
@Service
@RequiredArgsConstructor
public class LoginService {

  static final Duration REFRESH_TOKEN_TTL = Duration.ofHours(3);
  private static final int REFRESH_TOKEN_BYTES = 32; // 256비트 랜덤
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final MemberRepository memberRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtProvider jwtProvider;
  private final RefreshTokenRedisStore refreshTokenStore;

  // Access 토큰 유효 시간
  // application.yml의 jwt.access-token-expiration: 30m → Duration으로 자동 변환
  @Value("${jwt.access-token-expiration}")
  private Duration accessTokenExpiration;

  // 로그인: 회원 조회 → 비밀번호 확인 → Access 발급 → Refresh 발급·Redis 저장
  @Transactional(readOnly = true)
  public LoginResult login(LoginCommand command) {
    String email = normalizeEmail(command.email());

    // 이메일 없음과 비밀번호 불일치를 같은 에러로 처리
    Member member = memberRepository.findByEmail(email)
            .orElseThrow(() -> new BusinessException(MemberErrorCode.LOGIN_FAILED));
    if (!passwordEncoder.matches(command.password(), member.getPassword())) {
      throw new BusinessException(MemberErrorCode.LOGIN_FAILED);
    }

    String role = member.getRole().name();
    String accessToken = jwtProvider.createAccessToken(member.getId(), role);

    String refreshToken = newRefreshToken();
    refreshTokenStore.save(refreshToken, member.getId(), REFRESH_TOKEN_TTL);

    return new LoginResult(
            accessToken,
            accessTokenExpiration.toSeconds(),
            member.getNickname(),
            role,
            refreshToken,
            REFRESH_TOKEN_TTL
    );
  }

  // 추측 불가능한 랜덤 문자열(URL·쿠키에 안전한 Base64, 43자)
  private String newRefreshToken() {
    byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
    SECURE_RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  // SignupService·EmailVerificationService와 같은 규칙(가입 때 저장한 값과 일치해야 함)
  private String normalizeEmail(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }
}

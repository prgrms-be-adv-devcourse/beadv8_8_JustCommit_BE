package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.security.JwtProvider;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Locale;

// 소셜 로그인: 기존 회원이면 토큰 발급, 신규 회원이면 가입 토큰 발급
@Service
@RequiredArgsConstructor
public class SocialLoginService {

  private final MemberRepository memberRepository;
  private final JwtProvider jwtProvider;
  private final RefreshTokenIssuer refreshTokenIssuer;

  @Value("${jwt.access-token-expiration}")
  private Duration accessTokenExpiration;

  @Value("${jwt.signup-token-expiration}")
  private Duration signupTokenExpiration;

  @Transactional(readOnly = true)
  public SocialLoginResult login(SocialLoginCommand command) {
    // 기존 소셜 회원은 회원번호로 찾음 (카카오 이메일은 사용자가 바꿀 수 있지만 회원번호는 바뀌지 않음)
    Member member = memberRepository
            .findByProviderAndProviderId(command.provider(), command.providerId())
            .orElse(null);
    if (member != null) {
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

  // LoginService와 같은 방식으로 Access·Refresh 발급
  private LoginResult issueTokens(Member member) {
    String role = member.getRole().name();
    String accessToken = jwtProvider.createAccessToken(member.getId(), role);
    String refreshToken = refreshTokenIssuer.issue(member.getId());

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

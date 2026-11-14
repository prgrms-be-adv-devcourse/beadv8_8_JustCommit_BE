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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

// 소셜 로그인: 기존 회원이면 토큰 발급, 신규 회원이면 가입 토큰 발급
@Service
@RequiredArgsConstructor
public class SocialLoginService {

  private final MemberRepository memberRepository;
  private final JwtProvider jwtProvider;
  private final RefreshTokenIssuer refreshTokenIssuer;
  private final DatabaseClock databaseClock;

  @Value("${jwt.access-token-expiration}")
  private Duration accessTokenExpiration;

  @Value("${jwt.signup-token-expiration}")
  private Duration signupTokenExpiration;

  // 비밀번호 변경 · 탈퇴(같은 회원 행 잠금)와 겹치면 한쪽이 끝날 때까지 기다리고, 잠근 뒤 읽은 값으로 판단 (#409)
  @Transactional
  public SocialLoginResult login(SocialLoginCommand command) {
    // 기존 소셜 회원은 회원번호로 찾고 회원 행을 잠금 (카카오 이메일은 사용자가 바꿀 수 있지만 회원번호는 바뀌지 않음)
    // 행이 없을 때(신규)는 잠글 대상이 없음 → 동시 가입은 UNIQUE 제약 · 가입 처리가 막는다
    Member member = memberRepository
        .findByProviderAndProviderIdForUpdate(command.provider(), command.providerId())
        .orElse(null);
    if (member != null) {
      // 탈퇴 회원은 토큰을 발급하지 않음 (탈퇴 · 재가입 안내는 #407)
      if (member.getStatus() == MemberStatus.DELETED) {
        throw new BusinessException(MemberErrorCode.SOCIAL_LOGIN_FAILED);
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

package com.justcommit.backend.common.security;

import com.justcommit.backend.common.security.jwt.JwtProperties;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtProviderTest {

  private static final String SECRET = "test-only-jwt-secret-key-for-automated-tests-01234";
  private static final String OTHER_SECRET = "another-secret-key-used-to-simulate-forgery-56789";

  private final JwtProvider jwtProvider = createProvider(SECRET, Duration.ofMinutes(30));

  // 테스트용 JwtProvider 생성(Spring 없이 직접 생성)
  private static JwtProvider createProvider(String secret, Duration expiration) {
    return new JwtProvider(new JwtProperties(secret, expiration, expiration));
  }

  @Test
  @DisplayName("Access 토큰을 발급하고 검증하면 회원 ID와 권한을 꺼낼 수 있음")
  void createAndParseAccessToken() {
    String token = jwtProvider.createAccessToken(1L, "MEMBER");

    AuthMember authMember = jwtProvider.parseAccessToken(token);

    assertThat(authMember.memberId()).isEqualTo(1L);
    assertThat(authMember.role()).isEqualTo("MEMBER");
  }

  @Test
  @DisplayName("Signup 토큰을 발급하고 검증하면 provider와 providerId를 꺼낼 수 있음")
  void createAndParseSignupToken() {
    String token = jwtProvider.createSignupToken("kakao", "12345");

    SignupClaims claims = jwtProvider.parseSignupToken(token);

    assertThat(claims.provider()).isEqualTo("kakao");
    assertThat(claims.providerId()).isEqualTo("12345");
  }

  @Test
  @DisplayName("Signup토큰을 Access토큰으로 검증하면 INVALID")
  void signupTokenCannotBeUsedAsAccessToken() {
    String signupToken = jwtProvider.createSignupToken("kakao", "12345");

    assertThatThrownBy(() -> jwtProvider.parseAccessToken(signupToken))
            .isInstanceOf(TokenException.class)
            .extracting("reason").isEqualTo(TokenException.Reason.INVALID);
  }

  @Test
  @DisplayName("Access토큰을 Signup토큰으로 검증하면 INVALID")
  void accessTokenCannotBeUsedAsSignupToken() {
    String accessToken = jwtProvider.createAccessToken(1L, "MEMBER");

    assertThatThrownBy(() -> jwtProvider.parseSignupToken(accessToken))
            .isInstanceOf(TokenException.class)
            .extracting("reason").isEqualTo(TokenException.Reason.INVALID);
  }

  @Test
  @DisplayName("만료된 토큰을 검증하면 EXPIRED")
  void expiredToken() {
    JwtProvider expiredProvider = createProvider(SECRET, Duration.ofSeconds(-1));
    String expiredToken = expiredProvider.createAccessToken(1L, "MEMBER");

    assertThatThrownBy(() -> jwtProvider.parseAccessToken(expiredToken))
            .isInstanceOf(TokenException.class)
            .extracting("reason").isEqualTo(TokenException.Reason.EXPIRED);
  }

  @Test
  @DisplayName("다른 키로 서명된 토큰을 검증하면 INVALID")
  void tokenSignedWithOtherKey() {
    JwtProvider attacker = createProvider(OTHER_SECRET, Duration.ofMinutes(30));
    String forgedToken = attacker.createAccessToken(1L, "SELLER");

    assertThatThrownBy(() -> jwtProvider.parseAccessToken(forgedToken))
            .isInstanceOf(TokenException.class)
            .extracting("reason").isEqualTo(TokenException.Reason.INVALID);
  }

  @Test
  @DisplayName("JWT 형식이 아닌 문자열을 검증하면 INVALID")
  void malformedToken() {
    assertThatThrownBy(() -> jwtProvider.parseAccessToken("not-a-jwt"))
            .isInstanceOf(TokenException.class)
            .extracting("reason").isEqualTo(TokenException.Reason.INVALID);
  }

  @Test
  @DisplayName("secret이 32바이트보다 짧으면 JwtProvider 생성에 실패")
  void weakSecretKey() {
    assertThatThrownBy(() -> createProvider("too-short-secret", Duration.ofMinutes(30)))
            .isInstanceOf(WeakKeyException.class);
  }
}

package com.justcommit.backend.common.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtVerifierTest {

  private static final String SECRET = "test-only-jwt-secret-key-for-automated-tests-01234";
  private static final String OTHER_SECRET = "other-jwt-secret-key-for-automated-tests-56789";

  private final JwtVerifier verifier = new JwtVerifier(SECRET);

  @Test
  @DisplayName("Access 토큰을 검증하면 회원 ID와 권한을 꺼낼 수 있음")
  void parseAccessToken() {
    String token = token(SECRET, "ACCESS", Instant.now().plusSeconds(60));

    AuthMember authMember = verifier.parseAccessToken(token);

    assertThat(authMember.memberId()).isEqualTo(4L);
    assertThat(authMember.role()).isEqualTo("SELLER");
  }

  @Test
  @DisplayName("Signup 토큰을 Access 토큰으로 검증하면 INVALID")
  void signupTokenIsInvalid() {
    String token = token(SECRET, "SIGNUP", Instant.now().plusSeconds(60));

    assertReason(token, TokenException.Reason.INVALID);
  }

  @Test
  @DisplayName("만료된 토큰을 검증하면 EXPIRED")
  void expiredToken() {
    String token = token(SECRET, "ACCESS", Instant.now().minusSeconds(60));

    assertReason(token, TokenException.Reason.EXPIRED);
  }

  @Test
  @DisplayName("다른 키로 서명된 토큰을 검증하면 INVALID")
  void tokenSignedWithOtherKey() {
    String token = token(OTHER_SECRET, "ACCESS", Instant.now().plusSeconds(60));

    assertReason(token, TokenException.Reason.INVALID);
  }

  @Test
  @DisplayName("JWT 형식이 아닌 문자열을 검증하면 INVALID")
  void malformedToken() {
    assertReason("not-a-jwt", TokenException.Reason.INVALID);
  }

  @Test
  @DisplayName("secret이 32바이트보다 짧거나 비어 있으면 생성에 실패")
  void weakOrBlankSecret() {
    assertThatThrownBy(() -> new JwtVerifier("short-secret")).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> new JwtVerifier(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JwtVerifier(null)).isInstanceOf(IllegalArgumentException.class);
  }

  // 발급은 member 몫이라, 테스트용 토큰은 여기서 직접 만든다 (회원 4, SELLER)
  private static String token(String secret, String type, Instant expiresAt) {
    return Jwts.builder()
            .subject("4")
            .claim("type", type)
            .claim("role", "SELLER")
            .issuedAt(Date.from(Instant.now().minusSeconds(120)))
            .expiration(Date.from(expiresAt))
            .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
            .compact();
  }

  private void assertReason(String token, TokenException.Reason reason) {
    assertThatThrownBy(() -> verifier.parseAccessToken(token))
            .isInstanceOf(TokenException.class)
            .extracting(e -> ((TokenException) e).getReason())
            .isEqualTo(reason);
  }
}

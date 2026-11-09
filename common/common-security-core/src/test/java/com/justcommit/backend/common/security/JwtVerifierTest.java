package com.justcommit.backend.common.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtVerifierTest {

  private static final KeyPair KEY_PAIR = rsaKeyPair(2048);
  private static final KeyPair OTHER_KEY_PAIR = rsaKeyPair(2048);

  private final JwtVerifier verifier = new JwtVerifier(base64(KEY_PAIR));

  @Test
  @DisplayName("Access 토큰을 검증하면 회원 ID와 권한을 꺼낼 수 있음")
  void parseAccessToken() {
    String token = token(KEY_PAIR.getPrivate(), "ACCESS", Instant.now().plusSeconds(60));

    AuthMember authMember = verifier.parseAccessToken(token);

    assertThat(authMember.memberId()).isEqualTo(4L);
    assertThat(authMember.role()).isEqualTo("SELLER");
  }

  @Test
  @DisplayName("verifyAccessToken은 회원 정보와 발급 시각(iat, 초)을 함께 꺼냄")
  void verifyAccessTokenReturnsIssuedAt() {
    Instant issuedAt = Instant.now().minusSeconds(120);
    String token = token(KEY_PAIR.getPrivate(), "ACCESS", issuedAt, Instant.now().plusSeconds(60));

    VerifiedAccessToken verified = verifier.verifyAccessToken(token);

    assertThat(verified.member()).isEqualTo(new AuthMember(4L, "SELLER"));
    assertThat(verified.issuedAt()).isEqualTo(issuedAt.getEpochSecond());
  }

  @Test
  @DisplayName("iat가 없는 토큰은 verifyAccessToken에서 INVALID (끊은 시각과 비교할 수 없음)")
  void verifyAccessTokenWithoutIssuedAt() {
    String token = token(KEY_PAIR.getPrivate(), "ACCESS", null, Instant.now().plusSeconds(60));

    assertThatThrownBy(() -> verifier.verifyAccessToken(token))
            .isInstanceOf(TokenException.class)
            .extracting(e -> ((TokenException) e).getReason())
            .isEqualTo(TokenException.Reason.INVALID);
  }

  @Test
  @DisplayName("Signup 토큰을 Access 토큰으로 검증하면 INVALID")
  void signupTokenIsInvalid() {
    String token = token(KEY_PAIR.getPrivate(), "SIGNUP", Instant.now().plusSeconds(60));

    assertReason(token, TokenException.Reason.INVALID);
  }

  @Test
  @DisplayName("만료된 토큰을 검증하면 EXPIRED")
  void expiredToken() {
    String token = token(KEY_PAIR.getPrivate(), "ACCESS", Instant.now().minusSeconds(60));

    assertReason(token, TokenException.Reason.EXPIRED);
  }

  @Test
  @DisplayName("다른 개인키로 서명된 토큰을 검증하면 INVALID")
  void tokenSignedWithOtherKey() {
    String token = token(OTHER_KEY_PAIR.getPrivate(), "ACCESS", Instant.now().plusSeconds(60));

    assertReason(token, TokenException.Reason.INVALID);
  }

  @Test
  @DisplayName("HS256으로 서명된 토큰은 INVALID (대칭키 토큰 위조 차단)")
  void hs256TokenIsInvalid() {
    Key hmacKey = Keys.hmacShaKeyFor("any-hmac-secret-key-for-forgery-test-0123456789".getBytes(StandardCharsets.UTF_8));
    String token = token(hmacKey, "ACCESS", Instant.now().plusSeconds(60));

    assertReason(token, TokenException.Reason.INVALID);
  }

  @Test
  @DisplayName("JWT 형식이 아닌 문자열을 검증하면 INVALID")
  void malformedToken() {
    assertReason("not-a-jwt", TokenException.Reason.INVALID);
  }

  @Test
  @DisplayName("PEM 형식(머리말 · 줄바꿈)과 글자 \\n이 섞인 공개키도 읽음")
  void readsPemPublicKey() {
    String body = base64(KEY_PAIR);
    String pem = "-----BEGIN PUBLIC KEY-----\n" + body.substring(0, 64) + "\n" + body.substring(64)
            + "\n-----END PUBLIC KEY-----\n";
    String escaped = pem.replace("\n", "\\n");
    String token = token(KEY_PAIR.getPrivate(), "ACCESS", Instant.now().plusSeconds(60));

    assertThat(new JwtVerifier(pem).parseAccessToken(token).memberId()).isEqualTo(4L);
    assertThat(new JwtVerifier(escaped).parseAccessToken(token).memberId()).isEqualTo(4L);
  }

  @Test
  @DisplayName("공개키가 없거나 형식이 틀리거나 2048비트 미만이면 생성에 실패")
  void invalidPublicKey() {
    assertThatThrownBy(() -> new JwtVerifier(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JwtVerifier(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JwtVerifier("not-a-key")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JwtVerifier(base64(rsaKeyPair(1024))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("2048");
  }

  // 발급은 member 몫이라, 테스트용 토큰은 여기서 직접 만든다 (회원 4, SELLER)
  private static String token(Key signingKey, String type, Instant expiresAt) {
    return token(signingKey, type, Instant.now().minusSeconds(120), expiresAt);
  }

  private static String token(Key signingKey, String type, Instant issuedAt, Instant expiresAt) {
    return Jwts.builder()
            .subject("4")
            .claim("type", type)
            .claim("role", "SELLER")
            .issuedAt(issuedAt == null ? null : Date.from(issuedAt))
            .expiration(Date.from(expiresAt))
            .signWith(signingKey)
            .compact();
  }

  private void assertReason(String token, TokenException.Reason reason) {
    assertThatThrownBy(() -> verifier.parseAccessToken(token))
            .isInstanceOf(TokenException.class)
            .extracting(e -> ((TokenException) e).getReason())
            .isEqualTo(reason);
  }

  private static String base64(KeyPair keyPair) {
    return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
  }

  private static KeyPair rsaKeyPair(int bits) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(bits);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

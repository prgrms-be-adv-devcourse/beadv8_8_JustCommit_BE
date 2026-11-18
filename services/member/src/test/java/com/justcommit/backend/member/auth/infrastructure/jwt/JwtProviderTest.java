package com.justcommit.backend.member.auth.infrastructure.jwt;

import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.common.security.JwtVerifier;
import com.justcommit.backend.common.security.TokenException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtProviderTest {

  // 테스트 실행마다 새로 만드는 키 (파일에 남기지 않음)
  private static final KeyPair KEY_PAIR = newKeyPair(2048);
  private static final KeyPair OTHER_KEY_PAIR = newKeyPair(2048);

  private final JwtProvider jwtProvider = createProvider(KEY_PAIR, Duration.ofMinutes(30));

  // 공통 모듈의 Access 토큰 검증기 (Gateway·각 서비스와 같은 검증)
  private final JwtVerifier jwtVerifier = new JwtVerifier(publicKeyOf(KEY_PAIR));

  @Test
  @DisplayName("발급한 Access 토큰은 공통 JwtVerifier가 공개키로 검증하고 회원 ID와 권한을 꺼낼 수 있음")
  void accessToken_verifiedByCommonVerifier() {
    String token = jwtProvider.createAccessToken(1L, "MEMBER", Instant.now());

    AuthMember authMember = jwtVerifier.parseAccessToken(token);

    assertThat(authMember.memberId()).isEqualTo(1L);
    assertThat(authMember.role()).isEqualTo("MEMBER");
  }

  @Test
  @DisplayName("Signup 토큰을 발급하고 검증하면 provider · providerId · email을 꺼낼 수 있음")
  void createAndParseSignupToken() {
    String token = jwtProvider.createSignupToken("KAKAO", "12345", "user@kakao.com");

    SignupClaims claims = jwtProvider.parseSignupToken(token);

    assertThat(claims.provider()).isEqualTo("KAKAO");
    assertThat(claims.providerId()).isEqualTo("12345");
    assertThat(claims.email()).isEqualTo("user@kakao.com");
  }

  @Test
  @DisplayName("Signup 토큰은 공통 JwtVerifier에서 Access 토큰으로 쓸 수 없음 (INVALID)")
  void signupTokenCannotBeUsedAsAccessToken() {
    String signupToken = jwtProvider.createSignupToken("KAKAO", "12345", "user@kakao.com");

    assertThatThrownBy(() -> jwtVerifier.parseAccessToken(signupToken))
        .isInstanceOf(TokenException.class)
        .extracting("reason").isEqualTo(TokenException.Reason.INVALID);
  }

  @Test
  @DisplayName("Access 토큰을 Signup 토큰으로 검증하면 INVALID")
  void accessTokenCannotBeUsedAsSignupToken() {
    String accessToken = jwtProvider.createAccessToken(1L, "MEMBER", Instant.now());

    assertThatThrownBy(() -> jwtProvider.parseSignupToken(accessToken))
        .isInstanceOf(TokenException.class)
        .extracting("reason").isEqualTo(TokenException.Reason.INVALID);
  }

  @Test
  @DisplayName("만료된 Signup 토큰을 검증하면 EXPIRED")
  void expiredSignupToken() {
    JwtProvider expiredProvider = createProvider(KEY_PAIR, Duration.ofSeconds(-1));
    String expiredToken = expiredProvider.createSignupToken("KAKAO", "12345", "user@kakao.com");

    assertThatThrownBy(() -> jwtProvider.parseSignupToken(expiredToken))
        .isInstanceOf(TokenException.class)
        .extracting("reason").isEqualTo(TokenException.Reason.EXPIRED);
  }

  @Test
  @DisplayName("다른 개인키로 서명된 Signup 토큰을 검증하면 INVALID")
  void signupTokenSignedWithOtherKey() {
    JwtProvider attacker = createProvider(OTHER_KEY_PAIR, Duration.ofMinutes(30));
    String forgedToken = attacker.createSignupToken("KAKAO", "12345", "user@kakao.com");

    assertThatThrownBy(() -> jwtProvider.parseSignupToken(forgedToken))
        .isInstanceOf(TokenException.class)
        .extracting("reason").isEqualTo(TokenException.Reason.INVALID);
  }

  @Test
  @DisplayName("JWT 형식이 아닌 문자열을 검증하면 INVALID")
  void malformedToken() {
    assertThatThrownBy(() -> jwtProvider.parseSignupToken("not-a-jwt"))
        .isInstanceOf(TokenException.class)
        .extracting("reason").isEqualTo(TokenException.Reason.INVALID);
  }

  @Test
  @DisplayName("PEM 형식(머리말 · 줄바꿈)과 환경변수에 글자로 들어간 \\n도 읽음")
  void readsPemAndEscapedNewline() {
    String privatePem = pem("PRIVATE KEY", KEY_PAIR.getPrivate().getEncoded());
    String publicPemOneLine = pem("PUBLIC KEY", KEY_PAIR.getPublic().getEncoded()).replace("\n", "\\n");

    JwtProvider pemProvider = createProvider(privatePem, publicPemOneLine, Duration.ofMinutes(30));
    String token = pemProvider.createSignupToken("KAKAO", "12345", "user@kakao.com");

    assertThat(pemProvider.parseSignupToken(token).providerId()).isEqualTo("12345");
  }

  @Test
  @DisplayName("개인키가 없으면 생성 실패 (앱 시작 실패)")
  void missingPrivateKey() {
    assertThatThrownBy(() -> createProvider(" ", publicKeyOf(KEY_PAIR), Duration.ofMinutes(30)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("jwt.private-key가 설정되지 않았습니다");
  }

  @Test
  @DisplayName("개인키 자리에 개인키가 아닌 값을 넣으면 생성 실패")
  void invalidPrivateKeyFormat() {
    assertThatThrownBy(() -> createProvider(publicKeyOf(KEY_PAIR), publicKeyOf(KEY_PAIR), Duration.ofMinutes(30)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("jwt.private-key 형식이 올바르지 않습니다");
  }

  @Test
  @DisplayName("2048비트 미만 키면 생성 실패")
  void weakKey() {
    KeyPair weak = newKeyPair(1024);

    assertThatThrownBy(() -> createProvider(weak, Duration.ofMinutes(30)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("2048비트 이상");
  }

  @Test
  @DisplayName("개인키와 공개키가 한 쌍이 아니면 생성 실패")
  void mismatchedKeyPair() {
    assertThatThrownBy(() -> createProvider(privateKeyOf(KEY_PAIR), publicKeyOf(OTHER_KEY_PAIR), Duration.ofMinutes(30)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("한 쌍이 아닙니다");
  }

  // 테스트용 JwtProvider 생성 (Spring 없이 직접 생성)
  @Test
  @DisplayName("Access 토큰의 iat는 넘긴 시각의 초(밀리초 이하 버림), exp는 그 초 + 발급 수명 (#409)")
  void accessTokenTimesComeFromGivenInstant() {
    long second = Instant.now().getEpochSecond();
    Instant issuedAt = Instant.ofEpochSecond(second, 999_000_000);

    String token = jwtProvider.createAccessToken(4L, "MEMBER", issuedAt);

    assertThat(jwtVerifier.verifyAccessToken(token).issuedAt()).isEqualTo(second);
    assertThat(jwtProvider.getVerifiedAccessTokenExpiresAt(token).getEpochSecond())
        .isEqualTo(second + Duration.ofMinutes(30).toSeconds());
  }

  // ── 로그아웃 블랙리스트용 만료 시각 ──

  @Test
  @DisplayName("유효한 Access 토큰은 검증 후 토큰에 기록된 실제 만료 시각(exp)을 돌려준다")
  void verifiedAccessTokenExpiresAt() {
    String token = jwtProvider.createAccessToken(4L, "MEMBER", Instant.now());

    Instant expiresAt = jwtProvider.getVerifiedAccessTokenExpiresAt(token);

    // 같은 토큰의 iat(공통 검증기) + 발급 수명 30분 = exp
    long issuedAt = jwtVerifier.verifyAccessToken(token).issuedAt();
    assertThat(expiresAt.getEpochSecond()).isEqualTo(issuedAt + Duration.ofMinutes(30).toSeconds());
  }

  @Test
  @DisplayName("만료된 Access 토큰은 만료 시각을 돌려주지 않고 EXPIRED")
  void expiredAccessTokenExpiresAt() {
    String expiredToken = createProvider(KEY_PAIR, Duration.ofSeconds(-1)).createAccessToken(4L, "MEMBER", Instant.now());

    assertThatThrownBy(() -> jwtProvider.getVerifiedAccessTokenExpiresAt(expiredToken))
        .isInstanceOf(TokenException.class)
        .extracting("reason").isEqualTo(TokenException.Reason.EXPIRED);
  }

  @Test
  @DisplayName("Signup 토큰은 Access 만료 시각 조회에서 INVALID (타입 확인)")
  void signupTokenIsNotAccessForExpiresAt() {
    String signupToken = jwtProvider.createSignupToken("KAKAO", "12345", "user@kakao.com");

    assertThatThrownBy(() -> jwtProvider.getVerifiedAccessTokenExpiresAt(signupToken))
        .isInstanceOf(TokenException.class)
        .extracting("reason").isEqualTo(TokenException.Reason.INVALID);
  }

  @Test
  @DisplayName("다른 키로 서명했거나 형식이 깨진 토큰은 Access 만료 시각 조회에서 INVALID (서명 확인)")
  void forgedOrBrokenTokenExpiresAt() {
    String forgedToken = createProvider(OTHER_KEY_PAIR, Duration.ofMinutes(30)).createAccessToken(4L, "MEMBER", Instant.now());

    assertThatThrownBy(() -> jwtProvider.getVerifiedAccessTokenExpiresAt(forgedToken))
        .isInstanceOf(TokenException.class)
        .extracting("reason").isEqualTo(TokenException.Reason.INVALID);
    assertThatThrownBy(() -> jwtProvider.getVerifiedAccessTokenExpiresAt("not-a-jwt"))
        .isInstanceOf(TokenException.class)
        .extracting("reason").isEqualTo(TokenException.Reason.INVALID);
  }

  private static JwtProvider createProvider(KeyPair keyPair, Duration expiration) {
    return createProvider(privateKeyOf(keyPair), publicKeyOf(keyPair), expiration);
  }

  private static JwtProvider createProvider(String privateKey, String publicKey, Duration expiration) {
    return new JwtProvider(new JwtProperties(privateKey, publicKey, expiration, expiration));
  }

  // 개인키는 PKCS#8, 공개키는 X.509 형식의 한 줄 Base64
  private static String privateKeyOf(KeyPair keyPair) {
    return Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
  }

  private static String publicKeyOf(KeyPair keyPair) {
    return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
  }

  private static String pem(String type, byte[] der) {
    String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
    return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
  }

  private static KeyPair newKeyPair(int bits) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(bits);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

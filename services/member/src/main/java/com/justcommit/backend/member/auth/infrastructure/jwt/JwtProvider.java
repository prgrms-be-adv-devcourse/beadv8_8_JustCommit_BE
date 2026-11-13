package com.justcommit.backend.member.auth.infrastructure.jwt;

import com.justcommit.backend.common.security.TokenException;
import com.justcommit.backend.common.security.TokenType;
import io.jsonwebtoken.*;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

// JWT 발급(개인키 RS256 서명)과 Signup 토큰 검증(공개키)
// Access 토큰 검증은 공통 모듈의 JwtVerifier가 담당. 단, 로그아웃 블랙리스트에 필요한 만료 시각(exp)은
// 공통 검증 결과에 없어 여기서 같은 검증(서명 · 만료 · 타입)을 거쳐 꺼냄
@Component
public class JwtProvider {

  private static final String CLAIM_TYPE = "type";
  private static final String CLAIM_ROLE = "role";
  private static final String CLAIM_PROVIDER = "provider";
  private static final String CLAIM_EMAIL = "email";
  private static final int MIN_KEY_BITS = 2048;

  private final JwtProperties properties;
  private final RSAPrivateKey privateKey;
  private final JwtParser parser;

  // 앱 실행 시 한 번 호출. 키가 없거나 형식이 틀리거나 짝이 맞지 않으면 시작 실패
  public JwtProvider(JwtProperties properties) {
    this.properties = properties;
    this.privateKey = parsePrivateKey(properties.privateKey());
    RSAPublicKey publicKey = parsePublicKey(properties.publicKey());
    checkKeyPair(privateKey, publicKey);
    this.parser = Jwts.parser().verifyWith(publicKey).build();
  }

  // AccessToken 발급
  public String createAccessToken(Long memberId, String role) {
    return Jwts.builder()
        .subject(String.valueOf(memberId))
        .claim(CLAIM_TYPE, TokenType.ACCESS.name())
        .claim(CLAIM_ROLE, role)
        .issuedAt(Date.from(Instant.now()))
        .expiration(expiresAfter(properties.accessTokenExpiration()))
        .signWith(privateKey, Jwts.SIG.RS256)
        .compact();
  }

  // 소셜 로그인 신규 회원에게 SignupToken 발급
  // 추가정보 입력 API에서만 사용 가능
  public String createSignupToken(String provider, String providerId, String email) {
    return Jwts.builder()
        .subject(provider + ":" + providerId)
        .claim(CLAIM_TYPE, TokenType.SIGNUP.name())
        .claim(CLAIM_PROVIDER, provider)
        .claim(CLAIM_EMAIL, email)
        .issuedAt(Date.from(Instant.now()))
        .expiration(expiresAfter(properties.signupTokenExpiration()))
        .signWith(privateKey, Jwts.SIG.RS256)
        .compact();
  }

  // SignupToken 검증-> 소셜 식별 정보 꺼냄
  // member 모듈의 소셜 추가정보 입력 API에서 사용
  public SignupClaims parseSignupToken(String token) {
    Claims claims = parse(token, TokenType.SIGNUP);
    String provider = claims.get(CLAIM_PROVIDER, String.class);
    String subject = claims.getSubject();

    // subject 형식: "{provider}:{providerId}"
    if (provider == null || provider.isBlank() || subject == null
        || !subject.startsWith(provider + ":") || subject.length() <= provider.length() + 1) {
      throw new TokenException(TokenException.Reason.INVALID);
    }
    String providerId = subject.substring(provider.length() + 1);
    String email = claims.get(CLAIM_EMAIL, String.class);
    return new SignupClaims(provider, providerId, email);
  }

  // 로그아웃: 검증(서명 → 만료 → ACCESS 타입)을 통과한 Access 토큰의 만료 시각(exp)
  // 내용만 디코딩하지 않고 parse로 검증한 뒤 꺼냄. 블랙리스트 TTL(남은 수명) 계산에 사용
  // 만료 · 잘못된 토큰은 TokenException (호출한 쪽이 블랙리스트 저장만 건너뜀)
  public Instant getVerifiedAccessTokenExpiresAt(String token) {
    Claims claims = parse(token, TokenType.ACCESS);
    Date expiration = claims.getExpiration();
    if (expiration == null) {
      // exp가 없으면 남은 수명을 계산할 수 없음 (member가 발급한 토큰은 항상 exp가 있음)
      throw new TokenException(TokenException.Reason.INVALID);
    }
    return expiration.toInstant();
  }

  // 토큰 공통 검증, 서명-> 만료-> 타입
  private Claims parse(String token, TokenType expectedType) {
    Claims claims;
    try {
      claims = parser.parseSignedClaims(token).getPayload();
    } catch (ExpiredJwtException e) {
      throw new TokenException(TokenException.Reason.EXPIRED, e);
    } catch (JwtException | IllegalArgumentException e) {
      throw new TokenException(TokenException.Reason.INVALID, e);
    }

    if (!expectedType.name().equals(claims.get(CLAIM_TYPE, String.class))) {
      throw new TokenException(TokenException.Reason.INVALID);
    }
    return claims;
  }

  // duration을 만료 시각으로 계산
  private Date expiresAfter(Duration duration) {
    return Date.from(Instant.now().plus(duration));
  }

  // 개인키: PKCS#8 PEM 또는 한 줄 Base64
  private static RSAPrivateKey parsePrivateKey(String value) {
    byte[] der = decodeKey(value, "PRIVATE KEY", "jwt.private-key");
    RSAPrivateKey key;
    try {
      key = (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (GeneralSecurityException | ClassCastException e) {
      throw new IllegalArgumentException("jwt.private-key 형식이 올바르지 않습니다. (RSA 개인키 PKCS#8, PEM 또는 한 줄 Base64)", e);
    }
    checkKeySize(key.getModulus(), "jwt.private-key");
    return key;
  }

  // 공개키: X.509 PEM 또는 한 줄 Base64 (공통 JwtVerifier와 같은 형식)
  private static RSAPublicKey parsePublicKey(String value) {
    byte[] der = decodeKey(value, "PUBLIC KEY", "jwt.public-key");
    RSAPublicKey key;
    try {
      key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    } catch (GeneralSecurityException | ClassCastException e) {
      throw new IllegalArgumentException("jwt.public-key 형식이 올바르지 않습니다. (RSA 공개키, PEM 또는 한 줄 Base64)", e);
    }
    checkKeySize(key.getModulus(), "jwt.public-key");
    return key;
  }

  // PEM 머리말, 줄바꿈, 환경변수에 글자로 들어간 \n을 지우고 Base64 본문을 바이트로 바꿈
  private static byte[] decodeKey(String value, String type, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + "가 설정되지 않았습니다.");
    }
    String base64 = value
        .replace("-----BEGIN " + type + "-----", "")
        .replace("-----END " + type + "-----", "")
        .replace("\\n", "")
        .replaceAll("\\s", "");
    try {
      return Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(name + "가 Base64 형식이 아닙니다.", e);
    }
  }

  private static void checkKeySize(BigInteger modulus, String name) {
    if (modulus.bitLength() < MIN_KEY_BITS) {
      throw new IllegalArgumentException(name + "는 2048비트 이상이어야 합니다.");
    }
  }

  // 개인키와 공개키가 한 쌍인지 확인: 개인키로 서명한 값을 공개키로 검증해 봄
  // (짝이 다르면 발급한 토큰을 Gateway·다른 서비스가 검증하지 못함)
  private static void checkKeyPair(RSAPrivateKey privateKey, RSAPublicKey publicKey) {
    byte[] data = "jwt-key-pair-check".getBytes(StandardCharsets.UTF_8);
    boolean verified;
    try {
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(privateKey);
      signer.update(data);
      byte[] signature = signer.sign();

      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(publicKey);
      verifier.update(data);
      verified = verifier.verify(signature);
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("jwt.private-key와 jwt.public-key 짝 검사에 실패했습니다.", e);
    }
    if (!verified) {
      throw new IllegalArgumentException("jwt.private-key와 jwt.public-key가 한 쌍이 아닙니다.");
    }
  }
}

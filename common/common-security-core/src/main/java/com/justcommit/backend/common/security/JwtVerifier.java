package com.justcommit.backend.common.security;

import io.jsonwebtoken.*;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Access 토큰 검증 (RS256 서명 · 만료 · type). 서블릿 · 리액티브에 의존하지 않아 서비스 필터와 게이트웨이가 함께 쓴다.
 * 발급(개인키 서명)은 member만 한다.
 */
public class JwtVerifier {

  private static final String CLAIM_TYPE = "type";
  private static final String CLAIM_ROLE = "role";
  private static final int MIN_KEY_BITS = 2048;

  private final JwtParser parser;

  /**
   * @param publicKey RSA 공개키. PEM(머리말 · 줄바꿈 포함) 또는 한 줄 Base64.
   *                  없거나 형식이 틀리거나 2048비트 미만이면 생성할 때 실패한다.
   */
  public JwtVerifier(String publicKey) {
    this.parser = Jwts.parser()
        .verifyWith(parsePublicKey(publicKey))
        .build();
  }

  /**
   * 서명 → 만료 → type 순서로 확인하고 AuthMember로 바꾼다.
   *
   * @throws TokenException 만료면 EXPIRED, 그 밖의 문제는 INVALID
   */
  public AuthMember parseAccessToken(String token) {
    Claims claims;
    try {
      claims = parser.parseSignedClaims(token).getPayload();
    } catch (ExpiredJwtException e) {
      throw new TokenException(TokenException.Reason.EXPIRED, e);
    } catch (JwtException | IllegalArgumentException e) {
      throw new TokenException(TokenException.Reason.INVALID, e);
    }
    if (!TokenType.ACCESS.name().equals(claims.get(CLAIM_TYPE, String.class))) {
      throw new TokenException(TokenException.Reason.INVALID);
    }
    return new AuthMember(Long.valueOf(claims.getSubject()), claims.get(CLAIM_ROLE, String.class));
  }

  private static RSAPublicKey parsePublicKey(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("jwt.public-key가 설정되지 않았습니다.");
    }
    // PEM 머리말, 줄바꿈, 환경변수에 글자로 들어간 \n을 모두 지우고 Base64 본문만 남김
    String base64 = value
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", "")
        .replace("\\n", "")
        .replaceAll("\\s", "");
    RSAPublicKey key;
    try {
      byte[] der = Base64.getDecoder().decode(base64);
      key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    } catch (IllegalArgumentException | GeneralSecurityException | ClassCastException e) {
      throw new IllegalArgumentException("jwt.public-key 형식이 올바르지 않습니다. (RSA 공개키, PEM 또는 한 줄 Base64)", e);
    }
    if (key.getModulus().bitLength() < MIN_KEY_BITS) {
      throw new IllegalArgumentException("jwt.public-key는 2048비트 이상이어야 합니다.");
    }
    return key;
  }
}

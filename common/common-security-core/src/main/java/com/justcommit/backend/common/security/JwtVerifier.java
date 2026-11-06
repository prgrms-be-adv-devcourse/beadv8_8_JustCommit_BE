package com.justcommit.backend.common.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;

/**
 * Access 토큰 검증 (서명 · 만료 · type). 서블릿 · 리액티브에 의존하지 않아 서비스 필터와 게이트웨이가 함께 쓴다.
 * 발급은 member만 한다.
 */
public class JwtVerifier {

  private static final String CLAIM_TYPE = "type";
  private static final String CLAIM_ROLE = "role";

  private final JwtParser parser;

  /**
   * @param secret HS256 서명 키. 32바이트 미만이면 생성할 때 실패한다.
   */

  public JwtVerifier(String secret) {
    if (secret == null || secret.isBlank()) {
      throw new IllegalArgumentException("jwt.secret이 설정되지 않았습니다.");
    }
    this.parser = Jwts.parser()
        .verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
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
}

package com.justcommit.backend.common.security;

import com.justcommit.backend.common.security.jwt.JwtProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

// JWT 생성·검증을 담당 공개 컴포넌트
@Component
public class JwtProvider {

  private static final String CLAIM_TYPE = "type";
  private static final String CLAIM_ROLE = "role";
  private static final String CLAIM_PROVIDER = "provider";

  private final JwtProperties properties;
  private final SecretKey key;
  private final JwtParser parser;

  // 앱 실행 시 한 번 호출, 키와 파서를 미리 만들어서 재사용
  public JwtProvider(JwtProperties properties) {
    this.properties = properties;
    this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    this.parser = Jwts.parser().verifyWith(key).build();
  }

  // AccessToken 발급
  public String createAccessToken(Long memberId, String role) {
    return Jwts.builder()
            .subject(String.valueOf(memberId))
            .claim(CLAIM_TYPE, TokenType.ACCESS.name())
            .claim(CLAIM_ROLE, role)
            .issuedAt(Date.from(Instant.now()))
            .expiration(expiresAfter(properties.accessTokenExpiration()))
            .signWith(key, Jwts.SIG.HS256)
            .compact();
  }

  // 소셜 로그인 신규 회원에게 SignupToken 발급
  // 추가정보 입력 API에서만 사용 가능
  public String createSignupToken(String provider, String providerId) {
    return Jwts.builder()
            .subject(provider + ":" + providerId)
            .claim(CLAIM_TYPE, TokenType.SIGNUP.name())
            .claim(CLAIM_PROVIDER, provider)
            .issuedAt(Date.from(Instant.now()))
            .expiration(expiresAfter(properties.signupTokenExpiration()))
            .signWith(key, Jwts.SIG.HS256)
            .compact();
  }

  // AccessToken 검증, JWT 인증 필터에서 사용
  public AuthMember parseAccessToken(String token) {
    Claims claims = parse(token, TokenType.ACCESS);
    return new AuthMember(
            Long.valueOf(claims.getSubject()),
            claims.get(CLAIM_ROLE, String.class)
    );
  }

  // SignupToken 검증-> 소셜 식별 정보 꺼냄
  // member 모듈의 소셜 추가정보 입력 API에서 사용
  public SignupClaims parseSignupToken(String token) {
    Claims claims = parse(token, TokenType.SIGNUP);
    String provider = claims.get(CLAIM_PROVIDER, String.class);
    String providerId = claims.getSubject().substring(provider.length() + 1);
    return new SignupClaims(provider, providerId);
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
}

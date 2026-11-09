package com.justcommit.backend.gateway.auth;

import com.justcommit.backend.common.security.JwtVerifier;
import com.justcommit.backend.common.security.TokenException;
import com.justcommit.backend.common.security.VerifiedAccessToken;
import com.justcommit.backend.gateway.error.ErrorResponseWriter;
import com.justcommit.backend.gateway.error.GatewayErrorCode;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * Gateway 1차 토큰 검사. 공개 경로가 아니면
 * ① Access 토큰의 서명 · 만료 · type → ② Redis 폐기 여부(로그아웃 · 회원 단위) → ③ ADMIN 경로 권한 순서로 본다.
 * 통과하면 토큰을 바꾸지 않고 그대로 서비스로 넘긴다 (서비스가 2차 검사).
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

  private static final String BEARER_PREFIX = "Bearer ";
  private static final PathPattern ADMIN_PATH = PathPatternParser.defaultInstance.parse("/api/v1/admin/**");
  private static final String ADMIN_ROLE = "ADMIN";

  private final JwtVerifier jwtVerifier;
  private final TokenRevocationChecker revocationChecker;

  public JwtAuthenticationFilter(JwtVerifier jwtVerifier, TokenRevocationChecker revocationChecker) {
    this.jwtVerifier = jwtVerifier;
    this.revocationChecker = revocationChecker;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    if (PublicPaths.isPublic(exchange.getRequest())) {
      return chain.filter(exchange);
    }

    String token = resolveToken(exchange);
    if (token == null) {
      return reject(exchange, GatewayErrorCode.UNAUTHORIZED);
    }

    VerifiedAccessToken verified;
    try {
      verified = jwtVerifier.verifyAccessToken(token);
    } catch (TokenException e) {
      return reject(exchange, e.getReason() == TokenException.Reason.EXPIRED
          ? GatewayErrorCode.TOKEN_EXPIRED
          : GatewayErrorCode.INVALID_TOKEN);
    }

    return revocationChecker.isRevoked(token, verified)
        // Redis 오류만 503으로 바꿈. 아래 chain.filter(서비스 호출)의 오류는 여기서 잡지 않도록 먼저 결과를 정함
        .map(revoked -> revoked ? Decision.REVOKED : Decision.OK)
        .onErrorReturn(Decision.REDIS_UNAVAILABLE)
        .flatMap(decision -> switch (decision) {
          // 로그아웃 · 회원 단위 폐기된 토큰: 재발급으로 살릴 수 없으니 INVALID_TOKEN (다시 로그인)
          case REVOKED -> reject(exchange, GatewayErrorCode.INVALID_TOKEN);
          case REDIS_UNAVAILABLE -> reject(exchange, GatewayErrorCode.SERVICE_UNAVAILABLE);
          case OK -> isAdminPath(exchange) && !ADMIN_ROLE.equals(verified.member().role())
              ? reject(exchange, GatewayErrorCode.FORBIDDEN)
              : chain.filter(exchange);
        });
  }

  private enum Decision { OK, REVOKED, REDIS_UNAVAILABLE }

  private Mono<Void> reject(ServerWebExchange exchange, GatewayErrorCode errorCode) {
    return ErrorResponseWriter.write(exchange, errorCode);
  }

  private String resolveToken(ServerWebExchange exchange) {
    String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (header != null && header.startsWith(BEARER_PREFIX)) {
      return header.substring(BEARER_PREFIX.length());
    }
    return null;
  }

  private boolean isAdminPath(ServerWebExchange exchange) {
    PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
    return ADMIN_PATH.matches(path);
  }

  // 라우팅 직전 필터들보다 먼저 실행 (필터 순서: 접근 로그 → 인증 → 라우팅)
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 100;
  }
}

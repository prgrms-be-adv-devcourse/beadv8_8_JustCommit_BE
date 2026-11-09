package com.justcommit.backend.gateway.auth;

import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.common.security.JwtVerifier;
import com.justcommit.backend.common.security.TokenException;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * Gateway 1차 토큰 검사. 공개 경로가 아니면 Access 토큰의 서명 · 만료 · type을 확인하고,
 * ADMIN 경로는 권한까지 본다. 통과하면 토큰을 바꾸지 않고 그대로 서비스로 넘긴다 (서비스가 2차 검사).
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

  private static final String BEARER_PREFIX = "Bearer ";
  private static final PathPattern ADMIN_PATH = PathPatternParser.defaultInstance.parse("/api/v1/admin/**");
  private static final String ADMIN_ROLE = "ADMIN";

  private final JwtVerifier jwtVerifier;

  public JwtAuthenticationFilter(JwtVerifier jwtVerifier) {
    this.jwtVerifier = jwtVerifier;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    if (PublicPaths.isPublic(exchange.getRequest())) {
      return chain.filter(exchange);
    }

    String token = resolveToken(exchange);
    if (token == null) {
      return reject(exchange, HttpStatus.UNAUTHORIZED);
    }

    AuthMember member;
    try {
      member = jwtVerifier.parseAccessToken(token);
    } catch (TokenException e) {
      return reject(exchange, HttpStatus.UNAUTHORIZED);
    }

    if (isAdminPath(exchange) && !ADMIN_ROLE.equals(member.role())) {
      return reject(exchange, HttpStatus.FORBIDDEN);
    }
    return chain.filter(exchange);
  }

  // 응답 본문(ApiResponse)은 다음 단계(에러 응답)에서 붙임. 지금은 상태 코드만.
  private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
    exchange.getResponse().setStatusCode(status);
    return exchange.getResponse().setComplete();
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

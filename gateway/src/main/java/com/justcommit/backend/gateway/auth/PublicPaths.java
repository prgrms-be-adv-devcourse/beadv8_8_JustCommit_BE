package com.justcommit.backend.gateway.auth;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

/**
 * 토큰 없이 통과하는 경로 (docs/conventions/auth.md 「공개 경로」, docs/reference/external-apis.md).
 * 각 서비스 SecurityConfig에도 같은 경로가 있어야 한다. 한쪽만 바꾸면 401이 나거나 검사 없이 통과한다.
 */
class PublicPaths {

  /** method가 null이면 모든 메서드 */
  private record Rule(HttpMethod method, PathPattern pattern) {

    boolean matches(HttpMethod requestMethod, PathContainer path) {
      return (method == null || method.equals(requestMethod)) && pattern.matches(path);
    }
  }

  private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

  private static final List<Rule> RULES = List.of(
      // member: 이메일 인증 · 로그인 · 로그아웃 · 재발급 (로그아웃은 만료 토큰이어도 통과, 2026-11-09 결정)
      rule(null, "/api/v1/auth/**"),
      // member: 가입 · 소셜 가입(가입 토큰은 Access가 아니라 member가 직접 확인) · 중복 확인
      rule(HttpMethod.POST, "/api/v1/users"),
      rule(HttpMethod.POST, "/api/v1/users/social"),
      rule(HttpMethod.GET, "/api/v1/users/nicknameAvailability"),
      rule(HttpMethod.GET, "/api/v1/users/phoneAvailability"),
      // member: 카카오 로그인 (Spring Security OAuth2가 처리)
      rule(null, "/oauth2/**"),
      rule(null, "/login/oauth2/**"),
      // product: 상품 목록 · 상세, 품종. 상세는 숫자 id만 → products/me(SELLER)는 공개가 아님
      rule(HttpMethod.GET, "/api/v1/products"),
      rule(HttpMethod.GET, "/api/v1/products/{id:[0-9]+}"),
      rule(HttpMethod.GET, "/api/v1/species/**"),
      // market: 택배사 웹훅 (토큰 대신 market이 X-Api-Key 확인)
      rule(HttpMethod.POST, "/api/v1/webhooks/courier")
  );

  private PublicPaths() {
  }

  static boolean isPublic(ServerHttpRequest request) {
    PathContainer path = request.getPath().pathWithinApplication();
    return RULES.stream().anyMatch(r -> r.matches(request.getMethod(), path));
  }

  private static Rule rule(HttpMethod method, String pattern) {
    return new Rule(method, PARSER.parse(pattern));
  }
}

package com.justcommit.backend.gateway.ratelimit;

import com.justcommit.backend.gateway.auth.JwtAuthenticationFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 요청 수 제한을 회원별로 세기 위한 키 (챗봇 외부 AI 호출 비용 보호, #303).
 * 인증 필터가 토큰을 확인하고 남긴 회원 ID를 쓴다. 키가 없으면(인증 안 된 요청) SCG 기본대로 403이지만,
 * 로그인 경로는 인증 필터가 먼저 401로 막아 여기까지 오지 않는다. 라우트에서 #{@memberKeyResolver}로 고른다.
 */
@Component("memberKeyResolver")
public class MemberKeyResolver implements KeyResolver {

  @Override
  public Mono<String> resolve(ServerWebExchange exchange) {
    Object memberId = exchange.getAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE);
    return memberId == null ? Mono.empty() : Mono.just("member:" + memberId);
  }
}

package com.justcommit.backend.gateway.ratelimit;

import java.net.InetSocketAddress;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Primary;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 요청 수 제한을 IP별로 세기 위한 키. Nginx가 X-Forwarded-For를 접속한 IP($remote_addr) 하나로 덮어써서 넘기므로
 * 사용자가 헤더를 꾸며도 바뀌지 않는다 (docs/reference/ports.md). 헤더가 없으면(로컬 직접 호출) 접속 주소를 쓴다.
 * SCG 기본 키(로그인한 회원 이름)를 쓰지 않도록 @Primary.
 */
@Component
@Primary
public class ClientIpKeyResolver implements KeyResolver {

  private static final String X_FORWARDED_FOR = "X-Forwarded-For";

  @Override
  public Mono<String> resolve(ServerWebExchange exchange) {
    ServerHttpRequest request = exchange.getRequest();
    String forwarded = request.getHeaders().getFirst(X_FORWARDED_FOR);
    if (forwarded != null && !forwarded.isBlank()) {
      return Mono.just(forwarded.split(",")[0].trim());
    }
    InetSocketAddress remote = request.getRemoteAddress();
    return remote == null ? Mono.empty() : Mono.just(remote.getAddress().getHostAddress());
  }
}

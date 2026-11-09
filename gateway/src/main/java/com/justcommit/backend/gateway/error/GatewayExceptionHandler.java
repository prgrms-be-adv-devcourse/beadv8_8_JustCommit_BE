package com.justcommit.backend.gateway.error;

import io.netty.channel.ConnectTimeoutException;
import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

/**
 * 라우팅 중 생긴 에러를 ApiResponse로 바꾼다 (노션 정책 정리 10.8, docs/conventions/resilience.md).
 * <pre>
 * 서비스에 연결 못 함 (꺼짐 · 연결 1초 초과) → 503  요청이 서비스에 안 갔으니 다시 시도해도 안전
 * 응답 시간 초과                          → 504  서비스가 처리했을 수도 있음 (결과 불명)
 * 응답 받는 중 끊김 (정상 종료 · 강제 끊김)   → 502  결과 불명
 * 등록 안 된 경로 (/internal/** 포함)       → 404
 * 요청 수 제한 초과 (RequestRateLimiter)     → 429
 * 그 밖                                  → 500 + 에러 로그
 * </pre>
 * 스프링 기본 처리기(순서 -1)보다 먼저 실행되도록 순서 -2.
 */
@Component
@Order(-2)
public class GatewayExceptionHandler implements ErrorWebExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
    // 응답을 이미 보내기 시작했으면(SSE 도중 끊김 등) 상태 코드를 바꿀 수 없음 → 연결만 끝냄
    if (exchange.getResponse().isCommitted()) {
      return Mono.error(ex);
    }
    GatewayErrorCode errorCode = classify(ex);
    String path = exchange.getRequest().getPath().value();
    if (errorCode == GatewayErrorCode.INTERNAL_ERROR) {
      log.error("Gateway 처리 중 오류 path={}", path, ex);
    } else if (errorCode != GatewayErrorCode.RESOURCE_NOT_FOUND && errorCode != GatewayErrorCode.TOO_MANY_REQUESTS) {
      log.warn("서비스 호출 실패 {} path={} cause={}", errorCode.getStatus().value(), path, ex.toString());
    }
    return ErrorResponseWriter.write(exchange, errorCode);
  }

  static GatewayErrorCode classify(Throwable ex) {
    for (Throwable t = ex; t != null; t = t.getCause()) {
      if (t instanceof ConnectException || t instanceof ConnectTimeoutException) {
        return GatewayErrorCode.SERVICE_UNAVAILABLE;
      }
      if (t instanceof TimeoutException) {
        return GatewayErrorCode.GATEWAY_TIMEOUT;
      }
      // 연결이 끊긴 방식에 따라 PrematureCloseException(정상 종료) 또는 IOException(강제 끊김 · reset)
      // 연결 실패(ConnectException)도 IOException이라 위에서 먼저 걸러냄
      if (t instanceof PrematureCloseException || t instanceof IOException) {
        return GatewayErrorCode.BAD_GATEWAY;
      }
      // RequestRateLimiter의 throw-on-limit: 초과하면 429 HttpClientErrorException을 던짐
      if (t instanceof HttpClientErrorException.TooManyRequests) {
        return GatewayErrorCode.TOO_MANY_REQUESTS;
      }
      if (t instanceof ResponseStatusException rse) {
        GatewayErrorCode byStatus = byStatus(rse.getStatusCode());
        if (byStatus != null) {
          return byStatus;
        }
      }
    }
    return GatewayErrorCode.INTERNAL_ERROR;
  }

  // SCG가 응답 시간 초과를 504 ResponseStatusException으로, 경로 없음을 404로 던짐
  private static GatewayErrorCode byStatus(HttpStatusCode status) {
    if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
      return GatewayErrorCode.RESOURCE_NOT_FOUND;
    }
    if (status.isSameCodeAs(HttpStatus.GATEWAY_TIMEOUT)) {
      return GatewayErrorCode.GATEWAY_TIMEOUT;
    }
    if (status.isSameCodeAs(HttpStatus.SERVICE_UNAVAILABLE)) {
      return GatewayErrorCode.SERVICE_UNAVAILABLE;
    }
    if (status.isSameCodeAs(HttpStatus.BAD_GATEWAY)) {
      return GatewayErrorCode.BAD_GATEWAY;
    }
    return null;
  }
}

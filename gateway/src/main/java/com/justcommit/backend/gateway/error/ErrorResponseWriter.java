package com.justcommit.backend.gateway.error;

import com.justcommit.backend.common.exception.ErrorCode;
import com.justcommit.backend.common.response.ApiResponse;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * 에러를 팀 공통 ApiResponse(JSON)로 응답한다. 필터 단계라 @RestControllerAdvice가 없어서 직접 쓴다.
 */
public final class ErrorResponseWriter {

  private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

  private ErrorResponseWriter() {
  }

  public static Mono<Void> write(ServerWebExchange exchange, ErrorCode errorCode) {
    ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(errorCode.getStatus());
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    byte[] body = JSON_MAPPER.writeValueAsBytes(ApiResponse.error(errorCode));
    DataBuffer buffer = response.bufferFactory().wrap(body);
    return response.writeWith(Mono.just(buffer));
  }
}

package com.justcommit.backend.common.messaging.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.function.Supplier;

/**
 * 지금 요청의 추적 정보를 W3C traceparent(00-traceId-spanId-플래그)로 만든다 (#301, ADR 0027).
 * Outbox는 저장했다가 나중에(커밋 직후 · 재전송) 보내므로, 저장하는 순간의 값을 행에 남겨 Kafka 헤더로 넘긴다.
 * 받는 서비스의 Kafka 리스너가 이 헤더를 이어받아 같은 traceId로 Tempo에 한 줄로 이어진다.
 * 진행 중인 추적이 없으면(스케줄러 등) null → 헤더를 붙이지 않는다.
 */
class CurrentTraceparent implements Supplier<String> {

  private final Tracer tracer;

  CurrentTraceparent(Tracer tracer) {
    this.tracer = tracer;
  }

  @Override
  public String get() {
    Span span = tracer.currentSpan();
    if (span == null) {
      return null;
    }
    TraceContext context = span.context();
    String flags = Boolean.TRUE.equals(context.sampled()) ? "01" : "00";
    return "00-" + context.traceId() + "-" + context.spanId() + "-" + flags;
  }
}

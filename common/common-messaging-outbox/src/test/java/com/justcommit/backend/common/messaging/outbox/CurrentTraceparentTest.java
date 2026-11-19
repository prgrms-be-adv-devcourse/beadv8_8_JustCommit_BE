package com.justcommit.backend.common.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CurrentTraceparentTest {

  private final SimpleTracer tracer = new SimpleTracer();
  private final CurrentTraceparent traceparent = new CurrentTraceparent(tracer);

  @Test
  @DisplayName("진행 중인 추적이 있으면 W3C traceparent(00-traceId-spanId-플래그)를 만든다")
  void formatsCurrentSpan() {
    Span span = tracer.nextSpan().name("주문").start();
    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
      // 플래그는 샘플링 여부(01 · 00). 운영은 샘플링 100%라 01, 테스트용 SimpleTracer는 기본 00
      assertThat(traceparent.get())
          .matches("00-" + span.context().traceId() + "-" + span.context().spanId() + "-0[01]");
    } finally {
      span.end();
    }
  }

  @Test
  @DisplayName("진행 중인 추적이 없으면(스케줄러 등) null → 헤더를 붙이지 않는다")
  void nullWithoutSpan() {
    assertThat(traceparent.get()).isNull();
  }
}

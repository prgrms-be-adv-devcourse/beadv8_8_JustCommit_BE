package com.justcommit.backend.ai.product.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

// 리스너가 던지는 예외에 따라 바로 DLT로 가는지(재시도 없음) 확인한다.
// 공통 설정(MessagingConsumerAutoConfiguration)과 같은 DefaultErrorHandler에, DLT 전송 대신 호출 수만 세는 recoverer를 붙임
class ProductEventRetryPolicyTest {

    private final AtomicInteger sentToDlt = new AtomicInteger();
    private final DefaultErrorHandler handler = newHandler();
    private final ConsumerRecord<String, String> record = new ConsumerRecord<>("product.events.v1", 0, 0L, "1", "{}");

    private DefaultErrorHandler newHandler() {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                (failed, exception) -> sentToDlt.incrementAndGet(), new FixedBackOff(0L, 3L));
        errorHandler.addNotRetryableExceptions(JacksonException.class);
        return errorHandler;
    }

    @Test
    @DisplayName("봉투 version 2 미만(MessageConversionException)은 첫 실패에서 바로 DLT로 보낸다")
    void envelopeVersionErrorGoesToDltWithoutRetry() {
        boolean recovered = fail(new MessageConversionException("상품 이벤트 payload version 2 미만: version=1"));

        assertThat(recovered).isTrue();
        assertThat(sentToDlt).hasValue(1);
    }

    @Test
    @DisplayName("비교용: 일반 처리 실패는 첫 실패에서 DLT로 보내지 않고 재시도한다")
    void processingErrorIsRetried() {
        boolean recovered = fail(new IllegalStateException("DB 오류"));

        assertThat(recovered).isFalse();
        assertThat(sentToDlt).hasValue(0);
    }

    // 컨테이너처럼 리스너 예외를 ListenerExecutionFailedException으로 감싸 넘긴다
    private boolean fail(RuntimeException cause) {
        return handler.handleOne(new ListenerExecutionFailedException("listener failed", cause), record,
                mock(Consumer.class), mock(MessageListenerContainer.class));
    }
}

package com.justcommit.backend.member.infrastructure.messaging;

import com.justcommit.backend.member.MemberRegisteredEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class MemberEventKafkaPublisherTest {

  @Mock
  KafkaTemplate<String, Object> kafkaTemplate;

  @InjectMocks
  MemberEventKafkaPublisher publisher;

  @Test
  @DisplayName("회원 생성 이벤트를 토픽에 memberId를 key로 전송")
  void publish() {
    MemberRegisteredEvent event = new MemberRegisteredEvent(7L, false);
    given(kafkaTemplate.send(anyString(), anyString(), any()))
            .willReturn(new CompletableFuture<>());

    publisher.publish(event);

    then(kafkaTemplate).should().send("MemberRegisteredEvent", "7", event);
  }

  @Test
  @DisplayName("카프카 전송이 실패해도 예외를 밖으로 던지지 않는다")
  void publishFailure() {
    MemberRegisteredEvent event = new MemberRegisteredEvent(7L, false);
    given(kafkaTemplate.send(anyString(), anyString(), any()))
            .willReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

    assertThatCode(() -> publisher.publish(event)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("send 호출 순간 예외가 나도 밖으로 던지지 않는다")
  void publishThrows() {
    MemberRegisteredEvent event = new MemberRegisteredEvent(7L, false);
    given(kafkaTemplate.send(anyString(), anyString(), any()))
            .willThrow(new RuntimeException("broker not available"));

    assertThatCode(() -> publisher.publish(event)).doesNotThrowAnyException();
  }
}

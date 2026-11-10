package com.justcommit.backend.common.messaging.outbox;

import com.justcommit.backend.common.messaging.EventEnvelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class OutboxRelayTest {

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
  private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
  private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

  @Test
  @DisplayName("스레드 실행이 거부돼도(앱 종료 중 등) 커밋한 쪽에 예외를 넘기지 않는다")
  void rejectedExecutionDoesNotPropagate() {
    Executor rejecting = command -> {
      throw new RejectedExecutionException("shutting down");
    };
    OutboxRelay relay = new OutboxRelay(kafkaTemplate, repository, transactionManager, rejecting, Clock.systemUTC());

    assertThatCode(() -> relay.publishAfterCommit(event())).doesNotThrowAnyException();
    verifyNoInteractions(kafkaTemplate);
  }

  @Test
  @DisplayName("동시에 보내는 자리가 다 차면 바로 보내지 않고 재전송에 맡긴다")
  void skipsWhenAllPermitsInUse() {
    // 실행만 받아 두고 끝내지 않는 실행기: 자리를 반납하지 않으므로 MAX_CONCURRENT_SENDS개 뒤에는 자리가 없음
    int[] started = {0};
    Executor holding = command -> started[0]++;
    OutboxRelay relay = new OutboxRelay(kafkaTemplate, repository, transactionManager, holding, Clock.systemUTC());

    for (int i = 0; i < OutboxRelay.MAX_CONCURRENT_SENDS + 5; i++) {
      relay.publishAfterCommit(event());
    }

    assertThat(started[0]).isEqualTo(OutboxRelay.MAX_CONCURRENT_SENDS);
  }

  private static OutboxEvent event() {
    EventEnvelope envelope = new EventEnvelope(UUID.randomUUID().toString(), "TestEvent", 1,
        OffsetDateTime.now(), "7", JsonNodeFactory.instance.objectNode());
    return OutboxEvent.pending(envelope, "outbox-test", "{}", null, Instant.now());
  }
}

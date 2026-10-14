package com.justcommit.backend.member.infrastructure.messaging;

import com.justcommit.backend.member.MemberRegisteredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 회원 생성 이벤트를 커밋 후 카프카로 전송 (롤백되면 전송x)
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberEventKafkaPublisher {

  private final KafkaTemplate<String, Object> kafkaTemplate;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void publish(MemberRegisteredEvent event) {
    try {
      // key = memberId → 같은 회원 메시지는 같은 파티션으로
      kafkaTemplate.send(MemberRegisteredEvent.TOPIC, String.valueOf(event.memberId()), event)
              .whenComplete((result, ex) -> {
                if (ex != null) {
                  log.error("회원 생성 이벤트 카프카 전송 실패: {}", event, ex);
                }
              });
    } catch (RuntimeException e) {
      log.error("회원 생성 이벤트 카프카 전송 실패: {}", event, e);
    }
  }
}

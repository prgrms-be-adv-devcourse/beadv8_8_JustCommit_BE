package com.justcommit.backend.market.member.infrastructure.messaging;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.market.member.application.MemberReplicaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// member.address.events.v1 → 회원 복제본의 판매자 배송지 (#275)
// payload 빈 칸은 형식 오류로 바로 DLT. 복호화 실패 · memberId 불일치는 공통 설정대로 1 · 2 · 4초 재시도 후 DLT
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberAddressEventListener {

  private final EventEnvelopeMapper envelopeMapper;
  private final MemberReplicaService memberReplicaService;

  @KafkaListener(topics = KafkaTopics.MEMBER_ADDRESS_EVENTS)
  public void on(EventEnvelope envelope) {
    if ("MemberAddressChanged".equals(envelope.eventType())) {
      MemberAddressChanged event = envelopeMapper.readPayload(envelope, MemberAddressChanged.class);
      memberReplicaService.applySellerAddresses(event.memberId(), event.encryptedAddresses(), envelope.occurredAt());
      return;
    }
    // 나중에 member가 새 이벤트를 추가해도 market이 멈추지 않게 건너뜀
    log.debug("market이 쓰지 않는 주소 이벤트라 건너뜀: eventType={}, eventId={}",
        envelope.eventType(), envelope.eventId());
  }

  // member가 보내는 payload (docs/reference/kafka-topics.md 「member 판매자 배송지」). 빈 칸이면 형식 오류로 바로 DLT
  record MemberAddressChanged(Long memberId, String encryptedAddresses) {
    MemberAddressChanged {
      if (memberId == null || encryptedAddresses == null || encryptedAddresses.isBlank()) {
        throw new IllegalArgumentException("memberId 또는 encryptedAddresses가 없는 주소 이벤트");
      }
    }
  }
}

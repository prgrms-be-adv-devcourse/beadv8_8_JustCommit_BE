package com.justcommit.backend.market.member.infrastructure.messaging;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.market.member.application.MemberReplicaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// member.events.v1 → 회원 복제본 (#275).
// 실패하면 공통 설정이 1 · 2 · 4초 재시도 후 member.events.v1.DLT로 보낸다
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberEventListener {

  private final EventEnvelopeMapper envelopeMapper;
  private final MemberReplicaService memberReplicaService;

  @KafkaListener(topics = KafkaTopics.MEMBER_EVENTS)
  public void on(EventEnvelope envelope) {
    switch (envelope.eventType()) {
      case "MemberCreated", "MemberUpdated" -> {
        MemberChanged event = envelopeMapper.readPayload(envelope, MemberChanged.class);
        memberReplicaService.applyChanged(event.memberId(), event.nickname(), event.role(), event.seller(),
            envelope.occurredAt());
      }
      case "MemberWithdrawn" -> {
        MemberWithdrawn event = envelopeMapper.readPayload(envelope, MemberWithdrawn.class);
        memberReplicaService.applyWithdrawn(event.memberId(), envelope.occurredAt());
      }
      // 나중에 member가 새 이벤트를 추가해도 market이 멈추지 않게 건너뜀
      default -> log.debug("market이 쓰지 않는 회원 이벤트라 건너뜀: eventType={}, eventId={}",
          envelope.eventType(), envelope.eventId());
    }
  }

  // member가 보내는 payload (docs/services/member.md 「발행 이벤트」). memberId가 없으면 형식 오류로 바로 DLT
  record MemberChanged(Long memberId, String nickname, String role, boolean seller) {
    MemberChanged {
      if (memberId == null) {
        throw new IllegalArgumentException("memberId가 없는 회원 이벤트");
      }
    }
  }

  record MemberWithdrawn(Long memberId) {
    MemberWithdrawn {
      if (memberId == null) {
        throw new IllegalArgumentException("memberId가 없는 탈퇴 이벤트");
      }
    }
  }
}

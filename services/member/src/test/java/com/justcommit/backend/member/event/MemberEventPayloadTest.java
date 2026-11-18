package com.justcommit.backend.member.event;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.common.messaging.KafkaTopics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// product 담당과 합의한 member.events.v1 payload 모양 (#339). 칸을 바꾸면 받는 서비스와 다시 맞춰야 함
class MemberEventPayloadTest {

  private final EventEnvelopeMapper mapper = new EventEnvelopeMapper();

  @Test
  @DisplayName("MemberCreated: 봉투는 member.events.v1 · Key memberId, payload는 memberId · nickname · role · seller 네 칸")
  void memberCreatedPayload() {
    MemberCreated event = new MemberCreated(7L, "초록손", "MEMBER", false);

    EventEnvelope envelope = mapper.wrap(event);
    JsonNode payload = envelope.payload();

    assertThat(event.topic()).isEqualTo(KafkaTopics.MEMBER_EVENTS);
    assertThat(envelope.eventType()).isEqualTo("MemberCreated");
    assertThat(envelope.aggregateId()).isEqualTo("7");
    assertThat(fieldNames(payload)).containsExactlyInAnyOrder("memberId", "nickname", "role", "seller");
    assertThat(payload.get("memberId").isNumber()).isTrue();
    assertThat(payload.get("memberId").asLong()).isEqualTo(7L);
    assertThat(payload.get("nickname").asString()).isEqualTo("초록손");
    assertThat(payload.get("role").asString()).isEqualTo("MEMBER");
    assertThat(payload.get("seller").isBoolean()).isTrue();
    assertThat(payload.get("seller").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("MemberUpdated: MemberCreated와 같은 네 칸 (바뀐 뒤 전체 값)")
  void memberUpdatedPayload() {
    EventEnvelope envelope = mapper.wrap(new MemberUpdated(7L, "초록손", "ADMIN", true));

    assertThat(envelope.eventType()).isEqualTo("MemberUpdated");
    assertThat(fieldNames(envelope.payload())).containsExactlyInAnyOrder("memberId", "nickname", "role", "seller");
    assertThat(envelope.payload().get("seller").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("MemberWithdrawn: payload는 memberId 한 칸")
  void memberWithdrawnPayload() {
    EventEnvelope envelope = mapper.wrap(new MemberWithdrawn(7L));

    assertThat(envelope.eventType()).isEqualTo("MemberWithdrawn");
    assertThat(envelope.aggregateId()).isEqualTo("7");
    assertThat(fieldNames(envelope.payload())).containsExactly("memberId");
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.propertyNames().forEach(names::add);
    return names;
  }
}

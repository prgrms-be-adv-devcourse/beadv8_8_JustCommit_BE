package com.justcommit.backend.market.member.infrastructure.messaging;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.market.member.application.MemberReplicaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class MemberEventListenerTest {

  private static final OffsetDateTime OCCURRED_AT = OffsetDateTime.parse("2026-11-12T10:00:00+09:00");

  private final MemberReplicaService memberReplicaService = mock(MemberReplicaService.class);
  private final MemberEventListener listener = new MemberEventListener(new EventEnvelopeMapper(), memberReplicaService);

  @Test
  @DisplayName("MemberCreated를 받으면 바뀐 뒤 전체 값과 발생 시각으로 복제본을 반영한다")
  void memberCreated() {
    listener.on(envelope("MemberCreated", memberPayload("MEMBER", false)));

    verify(memberReplicaService).applyChanged(7L, "초록손", "MEMBER", false, OCCURRED_AT);
  }

  @Test
  @DisplayName("MemberUpdated도 같은 칸으로 반영한다 (ADMIN 판매자는 role ADMIN · seller true)")
  void memberUpdated() {
    listener.on(envelope("MemberUpdated", memberPayload("ADMIN", true)));

    verify(memberReplicaService).applyChanged(7L, "초록손", "ADMIN", true, OCCURRED_AT);
  }

  @Test
  @DisplayName("MemberWithdrawn을 받으면 탈퇴로 반영한다")
  void memberWithdrawn() {
    listener.on(envelope("MemberWithdrawn", JsonNodeFactory.instance.objectNode().put("memberId", 7L)));

    verify(memberReplicaService).applyWithdrawn(7L, OCCURRED_AT);
  }

  @Test
  @DisplayName("market이 쓰지 않는 이벤트는 멈추지 않고 건너뛴다")
  void ignoresUnknownEvent() {
    listener.on(envelope("MemberNicknameReserved", JsonNodeFactory.instance.objectNode().put("memberId", 7L)));

    verifyNoInteractions(memberReplicaService);
  }

  @Test
  @DisplayName("memberId가 없는 payload는 Jackson 예외라 공통 설정이 재시도 없이 DLT로 보낸다")
  void rejectsPayloadWithoutMemberId() {
    ObjectNode payload = JsonNodeFactory.instance.objectNode().put("nickname", "초록손");

    assertThatThrownBy(() -> listener.on(envelope("MemberCreated", payload)))
        .isInstanceOf(JacksonException.class);
    verifyNoInteractions(memberReplicaService);
  }

  private static ObjectNode memberPayload(String role, boolean seller) {
    return JsonNodeFactory.instance.objectNode()
        .put("memberId", 7L)
        .put("nickname", "초록손")
        .put("role", role)
        .put("seller", seller);
  }

  private static EventEnvelope envelope(String eventType, ObjectNode payload) {
    return new EventEnvelope("event-1", eventType, 1, OCCURRED_AT, "7", payload);
  }
}

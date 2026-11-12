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

class MemberAddressEventListenerTest {

  private static final OffsetDateTime OCCURRED_AT = OffsetDateTime.parse("2026-11-12T10:00:00+09:00");
  private static final String ENCRYPTED = "v1.k1:encrypted-addresses";

  private final MemberReplicaService memberReplicaService = mock(MemberReplicaService.class);
  private final MemberAddressEventListener listener =
      new MemberAddressEventListener(new EventEnvelopeMapper(), memberReplicaService);

  @Test
  @DisplayName("MemberAddressChanged를 받으면 memberId · 암호문 · 발생 시각을 그대로 서비스에 넘긴다")
  void memberAddressChanged() {
    ObjectNode payload = JsonNodeFactory.instance.objectNode()
        .put("memberId", 7L)
        .put("encryptedAddresses", ENCRYPTED);

    listener.on(envelope("MemberAddressChanged", payload));

    verify(memberReplicaService).applySellerAddresses(7L, ENCRYPTED, OCCURRED_AT);
  }

  @Test
  @DisplayName("market이 쓰지 않는 주소 이벤트는 멈추지 않고 건너뛴다")
  void ignoresUnknownEvent() {
    listener.on(envelope("MemberAddressDeleted", JsonNodeFactory.instance.objectNode().put("memberId", 7L)));

    verifyNoInteractions(memberReplicaService);
  }

  @Test
  @DisplayName("memberId가 없는 payload는 Jackson 예외다 (공통 설정상 재시도 없이 DLT로 가는 종류)")
  void rejectsPayloadWithoutMemberId() {
    ObjectNode payload = JsonNodeFactory.instance.objectNode().put("encryptedAddresses", ENCRYPTED);

    assertThatThrownBy(() -> listener.on(envelope("MemberAddressChanged", payload)))
        .isInstanceOf(JacksonException.class);
    verifyNoInteractions(memberReplicaService);
  }

  @Test
  @DisplayName("암호문이 빈 문자열인 payload도 Jackson 예외다")
  void rejectsBlankCiphertext() {
    ObjectNode payload = JsonNodeFactory.instance.objectNode()
        .put("memberId", 7L)
        .put("encryptedAddresses", " ");

    assertThatThrownBy(() -> listener.on(envelope("MemberAddressChanged", payload)))
        .isInstanceOf(JacksonException.class);
    verifyNoInteractions(memberReplicaService);
  }

  private static EventEnvelope envelope(String eventType, ObjectNode payload) {
    return new EventEnvelope("event-1", eventType, 1, OCCURRED_AT, "7", payload);
  }
}

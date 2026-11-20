package com.justcommit.backend.payment.infrastructure.messaging;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.payment.application.member.MemberReplicaService;
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

    private static final OffsetDateTime OCCURRED_AT = OffsetDateTime.parse("2026-11-20T10:00:00+09:00");

    private final MemberReplicaService memberReplicaService = mock(MemberReplicaService.class);
    private final MemberEventListener listener = new MemberEventListener(new EventEnvelopeMapper(), memberReplicaService);

    @Test
    @DisplayName("MemberCreated를 받으면 복제본 반영과 지갑 생성을 함께 부른다")
    void memberCreated() {
        listener.on(envelope("MemberCreated", memberPayload("MEMBER", false)));

        verify(memberReplicaService).applyCreated(7L, "초록손", "MEMBER", false, OCCURRED_AT);
    }

    @Test
    @DisplayName("MemberUpdated는 복제본만 반영한다")
    void memberUpdated() {
        listener.on(envelope("MemberUpdated", memberPayload("ADMIN", true)));

        verify(memberReplicaService).applyChanged(7L, "초록손", "ADMIN", true, OCCURRED_AT);
    }

    @Test
    @DisplayName("MemberWithdrawn을 받으면 복제본을 탈퇴로 반영한다")
    void memberWithdrawn() {
        listener.on(envelope("MemberWithdrawn", JsonNodeFactory.instance.objectNode().put("memberId", 7L)));

        verify(memberReplicaService).applyWithdrawn(7L, OCCURRED_AT);
    }

    @Test
    @DisplayName("payment가 쓰지 않는 이벤트는 멈추지 않고 건너뛴다")
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
        assertThatThrownBy(() -> listener.on(envelope("MemberWithdrawn", JsonNodeFactory.instance.objectNode())))
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

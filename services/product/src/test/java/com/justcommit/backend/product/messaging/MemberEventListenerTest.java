package com.justcommit.backend.product.messaging;

import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.product.member.application.MemberReplicaService;
import com.justcommit.backend.product.member.domain.MemberRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.core.JacksonException;

import java.time.OffsetDateTime;
import java.util.Map;

import static com.justcommit.backend.product.messaging.EventMessages.event;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class MemberEventListenerTest {

    private MemberReplicaService memberReplicaService;
    private MemberEventListener listener;

    @BeforeEach
    void setUp() {
        memberReplicaService = mock(MemberReplicaService.class);
        listener = new MemberEventListener(new EventEnvelopeMapper(), memberReplicaService);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"MemberCreated", "MemberUpdated"})
    @DisplayName("가입 · 정보 변경 이벤트면 닉네임 · 권한을 복제본에 반영한다")
    void profileEvents(String eventType) {
        listener.onMemberEvent(event(eventType, 7,
                Map.of("memberId", 7, "nickname", "식물집사", "role", "SELLER", "seller", true)));

        verify(memberReplicaService).applyProfile(eq(7L), eq("식물집사"), eq(MemberRole.SELLER), eq(true),
                any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("탈퇴 이벤트면 탈퇴를 반영한다")
    void withdrawnEvent() {
        listener.onMemberEvent(event("MemberWithdrawn", 7, Map.of("memberId", 7)));

        verify(memberReplicaService).applyWithdrawn(eq(7L), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("product가 모르는 이벤트 종류는 건너뛴다 (DLT 아님)")
    void unknownEventTypeIsSkipped() {
        listener.onMemberEvent(event("MemberAddressChanged", 7, Map.of("memberId", 7)));

        verifyNoInteractions(memberReplicaService);
    }

    @Test
    @DisplayName("필수 칸이 없거나 모르는 권한이면 형식 오류로 본다")
    void malformedPayload() {
        assertThatThrownBy(() -> listener.onMemberEvent(event("MemberCreated", 7, Map.of("memberId", 7))))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> listener.onMemberEvent(
                event("MemberCreated", 7, Map.of("memberId", 7, "nickname", "a", "role", "OWNER"))))
                .isInstanceOf(JacksonException.class);
        verifyNoInteractions(memberReplicaService);
    }
}

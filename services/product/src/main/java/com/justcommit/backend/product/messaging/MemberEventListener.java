package com.justcommit.backend.product.messaging;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.product.member.application.MemberReplicaService;
import com.justcommit.backend.product.messaging.event.MemberProfile;
import com.justcommit.backend.product.messaging.event.MemberWithdrawn;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// member.events.v1 → 회원 복제본 저장 · 갱신, 탈퇴한 판매자의 상품 정리
// 한 토픽에 세 이벤트가 오므로 eventType으로 나눈다. Key가 memberId라 한 회원의 이벤트는 순서대로 오지만,
// 재처리 등으로 늦게 와도 맞도록 발생 시각(occurredAt)으로 오래된 이벤트를 무시한다
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberEventListener {

    static final String MEMBER_CREATED = "MemberCreated";
    static final String MEMBER_UPDATED = "MemberUpdated";
    static final String MEMBER_WITHDRAWN = "MemberWithdrawn";

    private final EventReader eventReader;
    private final MemberReplicaService memberReplicaService;

    @KafkaListener(topics = "${MEMBER_EVENTS_TOPIC:" + KafkaTopics.MEMBER_EVENTS + "}")
    public void onMemberEvent(String message) {
        EventEnvelope envelope = eventReader.envelope(message);
        switch (envelope.eventType()) {
            case MEMBER_CREATED, MEMBER_UPDATED -> {
                MemberProfile profile = eventReader.payload(envelope, MemberProfile.class);
                boolean applied = memberReplicaService.applyProfile(
                        profile.memberId(), profile.nickname(), profile.role(), envelope.occurredAt());
                log.info("회원 복제본 반영: eventId={}, eventType={}, memberId={}, applied={}",
                        envelope.eventId(), envelope.eventType(), profile.memberId(), applied);
            }
            case MEMBER_WITHDRAWN -> {
                MemberWithdrawn withdrawn = eventReader.payload(envelope, MemberWithdrawn.class);
                int deleted = memberReplicaService.applyWithdrawn(withdrawn.memberId(), envelope.occurredAt());
                log.info("회원 탈퇴 반영: eventId={}, memberId={}, deletedProducts={}",
                        envelope.eventId(), withdrawn.memberId(), deleted);
            }
            // 새 이벤트 종류가 생겨도 product가 멈추지 않게 건너뜀 (DLT 아님)
            default -> log.info("product가 처리하지 않는 회원 이벤트: eventId={}, eventType={}",
                    envelope.eventId(), envelope.eventType());
        }
    }
}

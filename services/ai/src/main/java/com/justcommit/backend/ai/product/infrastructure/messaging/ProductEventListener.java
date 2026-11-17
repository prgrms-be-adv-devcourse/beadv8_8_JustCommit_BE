package com.justcommit.backend.ai.product.infrastructure.messaging;

import com.justcommit.backend.ai.product.domain.ProductSnapshot;
import com.justcommit.backend.ai.product.infrastructure.ProductSnapshotRepository;
import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.common.messaging.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// product.events.v1 → 상품 스냅샷 (#293, ADR 0021).
// 저장까지 리스너 스레드에서 끝낸다(같은 상품은 같은 파티션이라 순서대로 옴).
// DB 오류 같은 처리 실패는 공통 설정이 1 · 2 · 4초 재시도 후 product.events.v1.DLT로 보낸다
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEventListener {

    private final EventEnvelopeMapper envelopeMapper;
    private final ProductSnapshotRepository snapshotRepository;

    @KafkaListener(topics = KafkaTopics.PRODUCT_EVENTS)
    public void on(EventEnvelope envelope) {
        switch (envelope.eventType()) {
            case "ProductRegistered", "ProductUpdated", "ProductStatusChanged" -> {
                ProductChanged event = envelopeMapper.readPayload(envelope, ProductChanged.class);
                boolean applied = snapshotRepository.upsertIfNewer(event.toSnapshot());
                if (applied) {
                    log.debug("상품 스냅샷 반영: productId={}, status={}, productVersion={}, eventId={}",
                            event.productId(), event.status(), event.productVersion(), envelope.eventId());
                } else {
                    // 같은 버전이면 중복, 낮으면 늦게 온 이벤트. 정상이라 DLT로 보내지 않는다
                    log.info("저장된 버전보다 크지 않아 버림: productId={}, productVersion={}, eventType={}, "
                                    + "occurredAt={}, eventId={}",
                            event.productId(), event.productVersion(), envelope.eventType(),
                            envelope.occurredAt(), envelope.eventId());
                }
            }
            // ProductApproved · ProductRejected는 알림용. 상태는 함께 오는 ProductStatusChanged로 받는다
            default -> log.debug("ai가 쓰지 않는 상품 이벤트라 건너뜀: eventType={}, eventId={}",
                    envelope.eventType(), envelope.eventId());
        }
    }

    // product가 보내는 payload v2 (docs/reference/kafka-topics.md 「product 상품 이벤트」).
    // 칸이 비면(productId만 있던 봉투 version 1 포함) 생성자 예외 → Jackson 예외로 감싸져 재시도 없이 바로 DLT
    record ProductChanged(Long productId, Long speciesId, Long sellerId, String status, Boolean hidden,
            Long productVersion) {
        ProductChanged {
            if (productId == null || speciesId == null || sellerId == null || status == null || status.isBlank()
                    || hidden == null || productVersion == null) {
                throw new IllegalArgumentException("필수 칸이 없는 상품 이벤트 (payload version 2 아님?)");
            }
        }

        ProductSnapshot toSnapshot() {
            return new ProductSnapshot(productId, speciesId, sellerId, status, hidden, productVersion);
        }
    }
}

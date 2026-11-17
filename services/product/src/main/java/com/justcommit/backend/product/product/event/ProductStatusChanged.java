package com.justcommit.backend.product.product.event;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

// 상품 상태 · 숨김 변경 (예약 · 판매 완료 · 예약 해제 · 판매 재개 · 숨김 · 삭제 · 판매자 탈퇴 정리 · 검수 결과 · 이의 신청 · 재요청)
// payload v2: 바뀐 뒤 품종 · 판매자 · 상태 · 숨김 · 상품 버전 (docs/reference/kafka-topics.md, ADR 0021)
// 받는 쪽(ai 상품 스냅샷)은 productVersion이 저장된 값보다 클 때만 반영한다. 발행은 ProductEventPublisher로만
public record ProductStatusChanged(Long productId, Long speciesId, Long sellerId, String status, boolean hidden,
                     long productVersion) implements IntegrationEvent {

    public static ProductStatusChanged from(ProductEventSource source) {
        return new ProductStatusChanged(source.productId(), source.speciesId(), source.sellerId(),
                source.status().name(), source.hidden(), source.productVersion());
    }

    @Override
    public String topic() {
        return KafkaTopics.PRODUCT_EVENTS;
    }

    @Override
    public String eventType() {
        return "ProductStatusChanged";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(productId);
    }

    // v1(productId만) → v2(값을 실음)
    @Override
    public int version() {
        return 2;
    }
}

package com.justcommit.backend.product.product.event;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

// 상품 상태 · 숨김 변경 (예약 · 판매 완료 · 예약 해제 · 판매 재개 · 숨김 · 삭제). payload는 상품 ID만
public record ProductStatusChanged(Long productId) implements IntegrationEvent {

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
}

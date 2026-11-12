package com.justcommit.backend.product.product.event;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

// 상품 수정 (제목 · 가격 · 사진 · 품종 등). payload는 상품 ID만, 받는 쪽이 DB에서 최신 상품을 읽음
public record ProductUpdated(Long productId) implements IntegrationEvent {

    @Override
    public String topic() {
        return KafkaTopics.PRODUCT_EVENTS;
    }

    @Override
    public String eventType() {
        return "ProductUpdated";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(productId);
    }
}
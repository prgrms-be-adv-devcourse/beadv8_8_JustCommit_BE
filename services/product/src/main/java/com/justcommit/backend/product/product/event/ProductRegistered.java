package com.justcommit.backend.product.product.event;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

// 상품 등록. payload는 상품 ID만, 받는 쪽이 DB에서 최신 상품을 읽음 (docs/flows/search-recommend.md 「검색 색인」)
public record ProductRegistered(Long productId) implements IntegrationEvent {

    @Override
    public String topic() {
        return KafkaTopics.PRODUCT_EVENTS;
    }

    @Override
    public String eventType() {
        return "ProductRegistered";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(productId);
    }
}

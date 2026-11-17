package com.justcommit.backend.product.product.event;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

// 상품 등록
// payload v2: 바뀐 뒤 품종 · 판매자 · 상태 · 숨김 · 상품 버전 (docs/reference/kafka-topics.md, ADR 0021)
// 받는 쪽(ai 상품 스냅샷)은 productVersion이 저장된 값보다 클 때만 반영한다. 발행은 ProductEventPublisher로만
public record ProductRegistered(Long productId, Long speciesId, Long sellerId, String status, boolean hidden,
                     long productVersion) implements IntegrationEvent {

    public static ProductRegistered from(ProductEventSource source) {
        return new ProductRegistered(source.productId(), source.speciesId(), source.sellerId(),
                source.status().name(), source.hidden(), source.productVersion());
    }

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

    // v1(productId만) → v2(값을 실음)
    @Override
    public int version() {
        return 2;
    }
}

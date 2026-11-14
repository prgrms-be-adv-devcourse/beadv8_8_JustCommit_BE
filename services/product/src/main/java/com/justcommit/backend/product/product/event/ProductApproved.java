package com.justcommit.backend.product.product.event;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

// 검수 통과 알림 (자동 · 관리자). notification이 product를 조회하지 않고 이 값만으로 판매자에게 알림을 만듦
// 상품 상태 동기화는 같은 트랜잭션에서 함께 보내는 ProductStatusChanged가 맡음
public record ProductApproved(Long productId, Long sellerId, int reviewVersion, String productTitle) implements IntegrationEvent {

    @Override
    public String topic() {
        return KafkaTopics.PRODUCT_EVENTS;
    }

    @Override
    public String eventType() {
        return "ProductApproved";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(productId);
    }
}

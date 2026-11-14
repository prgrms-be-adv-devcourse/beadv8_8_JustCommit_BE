package com.justcommit.backend.product.product.event;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

// 검수 반려 알림 (자동 · 관리자). reasonCode는 반려 사유 코드(RejectReason 이름), reasonMessage는 판매자에게 보여 줄 문구
// (OTHER면 관리자가 입력한 내용). 상품 상태 동기화는 같은 트랜잭션의 ProductStatusChanged가 맡음
public record ProductRejected(Long productId, Long sellerId, int reviewVersion, String productTitle,
                              String reasonCode, String reasonMessage) implements IntegrationEvent {

    @Override
    public String topic() {
        return KafkaTopics.PRODUCT_EVENTS;
    }

    @Override
    public String eventType() {
        return "ProductRejected";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(productId);
    }
}

package com.justcommit.backend.product.product.event;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.time.LocalDateTime;

// 관리자 강제 숨김 판매자 알림 (#623). reason은 관리자가 입력한 사유(판매자에게 그대로 보임), hiddenAt은 알림 중복 키용
// 상품 상태 동기화(hidden=true)는 같은 트랜잭션의 ProductStatusChanged가 맡음
public record ProductAdminHidden(Long productId, Long sellerId, String productTitle, String reason,
                                 LocalDateTime hiddenAt) implements IntegrationEvent {

    @Override
    public String topic() {
        return KafkaTopics.PRODUCT_EVENTS;
    }

    @Override
    public String eventType() {
        return "ProductAdminHidden";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(productId);
    }
}

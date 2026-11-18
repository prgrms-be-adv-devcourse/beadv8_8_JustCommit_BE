package com.justcommit.backend.product.product.event;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

// 사진 검수 요청 (product.review.v1, 자기 토픽). 받는 쪽은 image_review의 같은 버전 행이 REQUESTED일 때만 검수
public record ProductReviewRequested(Long productId, int reviewVersion) implements IntegrationEvent {

    @Override
    public String topic() {
        return KafkaTopics.PRODUCT_REVIEW;
    }

    @Override
    public String eventType() {
        return "ProductReviewRequested";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(productId);
    }
}

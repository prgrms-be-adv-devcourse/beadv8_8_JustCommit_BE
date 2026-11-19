package com.justcommit.backend.product.product.event;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.time.LocalDateTime;

// 관리자 확인 12시간 지연 알림 (#509). 관리자 확인 지연 스케줄러(10분)가 이번에 새로 12시간을 넘긴 검수를 묶어 1개만 보냄
// delayedCount: 이번에 묶은 건수(전에 알린 건은 빠짐), checkedAt: 모은 시각
// 시각은 product 서버 시간(서울)의 LocalDateTime. 받는 쪽(notification)은 관리자에게 「검수 지연 N건」 알림을 만든다
public record ImageReviewDelayed(int delayedCount, LocalDateTime checkedAt)
        implements IntegrationEvent {

    // 상품 하나에 묶이지 않는 이벤트라 Key를 고정값으로 둠 (한 파티션으로 가서 지연 알림끼리 순서가 지켜짐, 10분에 최대 1개라 쏠림 걱정 없음)
    static final String AGGREGATE_ID = "image-review-delayed";

    @Override
    public String topic() {
        return KafkaTopics.PRODUCT_EVENTS;
    }

    @Override
    public String eventType() {
        return "ImageReviewDelayed";
    }

    @Override
    public String aggregateId() {
        return AGGREGATE_ID;
    }
}

package com.justcommit.backend.notification.infrastructure.messaging.event;

import java.time.LocalDateTime;

// 관리자 확인 12시간 지연 (product.events.v1, #509). delayedCount = 이번에 새로 12시간을 넘긴 건수, checkedAt = 모은 시각(서울)
public record ImageReviewDelayedEvent(int delayedCount, LocalDateTime checkedAt) {
}

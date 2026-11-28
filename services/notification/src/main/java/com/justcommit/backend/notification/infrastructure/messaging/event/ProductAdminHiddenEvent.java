package com.justcommit.backend.notification.infrastructure.messaging.event;

import java.time.LocalDateTime;

// 관리자 강제 숨김 (product.events.v1, #623). reason은 판매자에게 보여 줄 사유, hiddenAt은 숨긴 시각(알림 중복 키)
public record ProductAdminHiddenEvent(Long productId, Long sellerId, String productTitle, String reason,
                                      LocalDateTime hiddenAt) {
}

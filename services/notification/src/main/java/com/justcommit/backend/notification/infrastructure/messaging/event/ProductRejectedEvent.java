package com.justcommit.backend.notification.infrastructure.messaging.event;

// 상품 검수 반려 (product.events.v1). reasonMessage는 판매자에게 보여 줄 사유 문구
public record ProductRejectedEvent(Long productId, Long sellerId, int reviewVersion, String productTitle,
                                   String reasonCode, String reasonMessage) {
}

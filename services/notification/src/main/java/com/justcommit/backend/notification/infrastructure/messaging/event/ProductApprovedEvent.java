package com.justcommit.backend.notification.infrastructure.messaging.event;

// 상품 검수 통과 (product.events.v1). 검수할 때마다 reviewVersion이 올라 같은 상품을 다시 검수해도 구분된다
public record ProductApprovedEvent(Long productId, Long sellerId, int reviewVersion, String productTitle) {
}

package com.justcommit.backend.ai.product.domain;

// 상품 스냅샷 한 행 (product_snapshot). product.events.v1 payload v2 그대로 (#293, ADR 0021)
public record ProductSnapshot(
        long productId,
        long speciesId,
        long sellerId,
        String status,
        boolean hidden,
        long productVersion) {
}

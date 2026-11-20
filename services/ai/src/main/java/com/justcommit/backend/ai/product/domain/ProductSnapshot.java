package com.justcommit.backend.ai.product.domain;

import java.math.BigDecimal;

// 상품 스냅샷 한 행 (product_snapshot). product.events.v1 payload 그대로 (#293, ADR 0021)
// 제목부터 화분 크기까지는 payload v3 (챗봇 상품 문서 · 카드, ADR 0031). v2 이벤트로 저장된 행은 null
public record ProductSnapshot(
        long productId,
        long speciesId,
        long sellerId,
        String status,
        boolean hidden,
        long productVersion,
        String title,
        BigDecimal price,
        String thumbnailUrl,
        String description,
        String growNote,
        Integer heightCm,
        String potSize) {

    public static final String DELETED = "DELETED";

    // payload v2 (상품 문서 값 없음)
    public ProductSnapshot(long productId, long speciesId, long sellerId, String status, boolean hidden,
            long productVersion) {
        this(productId, speciesId, sellerId, status, hidden, productVersion, null, null, null, null, null, null, null);
    }

    public boolean hasDocument() {
        return title != null && !title.isBlank();
    }
}

package com.justcommit.backend.product.product.event;

import com.justcommit.backend.product.ProductStatus;
import java.math.BigDecimal;

/**
 * 상품 이벤트 payload를 만들 때 쓰는 DB 값 (ProductRepository.findEventSources).
 * 엔티티가 아니라 바뀐 뒤 DB 값을 바로 읽은 것이라, 일괄 UPDATE 뒤에도 새 상태 · 버전이 담긴다.
 * 제목부터 대표 사진까지는 payload v3 (ai 챗봇의 상품 문서 · 카드용, ADR 0031)
 */
public record ProductEventSource(
        Long productId,
        Long speciesId,
        Long sellerId,
        ProductStatus status,
        boolean hidden,
        long productVersion,
        String title,
        BigDecimal price,
        String thumbnailUrl,
        String description,
        String growNote,
        Integer heightCm,
        String potSize
) {
}

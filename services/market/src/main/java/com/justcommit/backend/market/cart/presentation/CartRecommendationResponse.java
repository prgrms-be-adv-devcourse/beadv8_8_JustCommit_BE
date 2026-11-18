package com.justcommit.backend.market.cart.presentation;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 장바구니 추천 카드: 카드 정보는 product 조회 결과, 추천 이유는 ai 응답.
// createdAt은 상품 등록 시각 (장바구니 목록 CartItemResponse.createdAt은 장바구니에 담은 시각이라 다름)
public record CartRecommendationResponse(
        Long productId,
        String title,
        BigDecimal price,
        String thumbnailUrl,
        String speciesName,
        String reason,
        LocalDateTime createdAt
) {
}

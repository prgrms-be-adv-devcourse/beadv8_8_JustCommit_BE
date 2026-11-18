package com.justcommit.backend.market.cart.presentation;

import java.math.BigDecimal;

// 장바구니 추천 카드: 카드 정보는 product 조회 결과, 추천 이유는 ai 응답
public record CartRecommendationResponse(
        Long productId,
        String title,
        BigDecimal price,
        String thumbnailUrl,
        String speciesName,
        String reason
) {
}

package com.justcommit.backend.ai.recommendation.presentation.internal;

import com.justcommit.backend.ai.recommendation.application.Recommendation;
import java.util.List;

// 응답 data: 추천 순서대로 상품 ID + 이유. market의 AiApi.CartRecommendationResult와 같은 모양
public record CartRecommendationResponse(List<Item> items) {

    public record Item(Long productId, String reason) {
    }

    public static CartRecommendationResponse from(List<Recommendation> recommendations) {
        return new CartRecommendationResponse(recommendations.stream()
                .map(r -> new Item(r.productId(), r.reason()))
                .toList());
    }
}

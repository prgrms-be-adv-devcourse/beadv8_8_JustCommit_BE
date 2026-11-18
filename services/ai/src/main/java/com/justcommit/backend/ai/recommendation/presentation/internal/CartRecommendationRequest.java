package com.justcommit.backend.ai.recommendation.presentation.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

// market → ai 장바구니 추천 요청 (docs/reference/internal-apis.md). market의 AiApi.CartRecommendationRequest와 같은 모양
// 목록이 null이면 빈 목록으로 본다. size는 market이 화면 size의 2배(최대 20)로 보내므로 받은 만큼만 돌려준다
public record CartRecommendationRequest(
        @Size(max = 100) List<@NotNull @Positive Long> cartProductIds,
        @Size(max = 100) List<@NotNull @Positive Long> purchasedProductIds,
        @Min(1) @Max(20) int size) {

    public CartRecommendationRequest {
        cartProductIds = cartProductIds == null ? List.of() : cartProductIds;
        purchasedProductIds = purchasedProductIds == null ? List.of() : purchasedProductIds;
    }
}

package com.justcommit.backend.ai.recommendation.presentation.internal;

import com.justcommit.backend.ai.recommendation.application.CartRecommendationService;
import com.justcommit.backend.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 장바구니 추천 내부 API (market만 부름, 내부 키 필요). 카드 정보 · 본인 상품 · 구매 불가 상품 제외는 market이 한다
@RestController
@RequestMapping("/internal/v1/recommendations")
@RequiredArgsConstructor
public class RecommendationInternalController {

    private final CartRecommendationService cartRecommendationService;

    @PostMapping("/cart")
    public ApiResponse<CartRecommendationResponse> recommendForCart(@Valid @RequestBody CartRecommendationRequest request) {
        return ApiResponse.ok("장바구니 추천을 조회했습니다.", CartRecommendationResponse.from(
                cartRecommendationService.recommend(
                        request.cartProductIds(), request.purchasedProductIds(), request.size())));
    }
}

package com.justcommit.backend.market.client.ai;

import com.justcommit.backend.common.response.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

@HttpExchange("/internal/v1/recommendations")
public interface AiApi {

    // 장바구니 추천 (docs/reference/internal-apis.md). 카드 정보 · 본인 상품 · 구매 불가 상품 제외는 market이 한다
    @PostExchange("/cart")
    ApiResponse<CartRecommendationResult> recommendForCart(@RequestBody CartRecommendationRequest request);

    record CartRecommendationRequest(List<Long> cartProductIds, List<Long> purchasedProductIds, int size) {
    }

    record CartRecommendationResult(List<Item> items) {

        public record Item(Long productId, String reason) {
        }
    }
}

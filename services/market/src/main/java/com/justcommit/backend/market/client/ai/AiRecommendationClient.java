package com.justcommit.backend.market.client.ai;

import com.justcommit.backend.common.client.exception.InternalApiException;
import com.justcommit.backend.common.response.ApiResponse;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
@RequiredArgsConstructor
class AiRecommendationClient implements CartRecommendationQuery {

    private final AiApi aiApi;

    @Override
    public List<Recommendation> recommendForCart(List<Long> cartProductIds, List<Long> purchasedProductIds, int size) {
        try {
            ApiResponse<AiApi.CartRecommendationResult> response = aiApi.recommendForCart(
                    new AiApi.CartRecommendationRequest(cartProductIds, purchasedProductIds, size));
            return toRecommendations(response);
        } catch (InternalApiException | RestClientException e) {
            // InternalApiException: 연결 실패 · 응답 타임아웃 · 4xx · 5xx, RestClientException: 본문 형식 오류
            log.warn("ai 장바구니 추천 실패, 빈 목록으로 응답: cart={}, purchased={}",
                    cartProductIds.size(), purchasedProductIds.size(), e);
            return List.of();
        }
    }

    private static List<Recommendation> toRecommendations(ApiResponse<AiApi.CartRecommendationResult> response) {
        if (response == null || response.data() == null || response.data().items() == null) {
            return List.of();
        }
        return response.data().items().stream()
                .filter(Objects::nonNull)
                .filter(item -> item.productId() != null)
                .map(item -> new Recommendation(item.productId(), item.reason()))
                .toList();
    }
}

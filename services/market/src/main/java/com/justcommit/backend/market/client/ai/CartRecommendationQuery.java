package com.justcommit.backend.market.client.ai;

import java.util.List;

public interface CartRecommendationQuery {

    // ai 연결 실패 · 응답 타임아웃(2초) · 에러 응답이면 예외 대신 빈 목록 (추천 영역만 비고 장바구니는 영향 없음)
    List<Recommendation> recommendForCart(List<Long> cartProductIds, List<Long> purchasedProductIds, int size);

    record Recommendation(Long productId, String reason) {
    }
}

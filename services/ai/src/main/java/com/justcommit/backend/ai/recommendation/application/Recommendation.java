package com.justcommit.backend.ai.recommendation.application;

// 추천 한 건: 판매 중 상품 ID + 템플릿 이유 문장
public record Recommendation(long productId, String reason) {
}

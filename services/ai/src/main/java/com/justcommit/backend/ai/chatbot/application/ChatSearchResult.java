package com.justcommit.backend.ai.chatbot.application;

import java.math.BigDecimal;
import java.util.List;

// 질문 벡터로 찾은 근거: 판매 중 상품 · 품종 문서 (유사도 높은 순)
public record ChatSearchResult(List<ProductHit> products, List<SpeciesHit> species) {

    public record ProductHit(long productId, long speciesId, String speciesName, String title, BigDecimal price,
            String thumbnailUrl, String document, double similarity) {
    }

    public record SpeciesHit(int speciesId, String name, String document, double similarity) {
    }
}

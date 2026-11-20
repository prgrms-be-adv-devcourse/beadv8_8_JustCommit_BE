package com.justcommit.backend.ai.search.application;

import java.util.List;

// 품종 하나: 이름 · 유사도(1 - 코사인 거리) · 그 품종의 판매 중 상품 ID(최신순)
public record SpeciesSearchResult(int speciesId, String name, double similarity, List<Long> productIds) {
}

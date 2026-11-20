package com.justcommit.backend.ai.search.presentation;

import com.justcommit.backend.ai.search.application.SpeciesSearchResult;
import java.util.List;

// 응답 data: 유사도 순 품종 목록. 결과가 없으면 items가 빈 목록
public record SpeciesSearchResponse(List<Item> items) {

    public record Item(int speciesId, String name, double similarity, List<Long> productIds) {
    }

    public static SpeciesSearchResponse from(List<SpeciesSearchResult> results) {
        return new SpeciesSearchResponse(results.stream()
                .map(r -> new Item(r.speciesId(), r.name(), r.similarity(), r.productIds()))
                .toList());
    }
}

package com.justcommit.backend.product.review.domain;

import com.justcommit.backend.product.review.domain.enums.SaleForm;

import java.util.Comparator;
import java.util.List;

// Haiku 식물 식별 결과. 후보는 확신도 높은 순으로 정렬해 둔다 (엔진이 순서를 틀려도 1순위가 맞게)
public record PlantIdentification(boolean plant, SaleForm saleForm, List<Candidate> candidates) {

    public PlantIdentification {
        candidates = candidates == null ? List.of()
                : candidates.stream().sorted(Comparator.comparingDouble(Candidate::confidence).reversed()).toList();
    }
}

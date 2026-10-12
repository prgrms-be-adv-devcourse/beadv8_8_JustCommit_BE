package com.justcommit.backend.product.presentation.dto;

import com.justcommit.backend.common.request.PagingRequest;
import com.justcommit.backend.product.domain.enums.Difficulty;
import com.justcommit.backend.product.domain.enums.Light;
import com.justcommit.backend.product.domain.enums.SpeciesCategory;
import com.justcommit.backend.product.domain.enums.Watering;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;

public record ProductSearchRequest
        (  String keyword,
           Long speciesId,
           SpeciesCategory category,
           Light light,
           Watering watering,
           Difficulty difficulty,
           BigDecimal minPrice,
           BigDecimal maxPrice,
           String sort,
           Integer page,
           Integer size
        ) implements PagingRequest {

    @Override
    public Sort defaultSort() {

        return switch (sort) {
            case "priceAsc" -> Sort.by(Sort.Direction.ASC, "price");
            case "priceDesc" -> Sort.by(Sort.Direction.DESC, "price");
            case null, default -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
    }
}
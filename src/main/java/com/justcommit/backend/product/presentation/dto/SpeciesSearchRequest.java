package com.justcommit.backend.product.presentation.dto;

import com.justcommit.backend.common.request.PagingRequest;
import com.justcommit.backend.product.domain.enums.SpeciesCategory;
import org.springframework.data.domain.Sort;

public record SpeciesSearchRequest(
        String keyword,
        SpeciesCategory category,
        Integer page,
        Integer size
)implements PagingRequest {

    @Override
    public Sort defaultSort() {
        return Sort.by(Sort.Direction.ASC, "name");
    }
}

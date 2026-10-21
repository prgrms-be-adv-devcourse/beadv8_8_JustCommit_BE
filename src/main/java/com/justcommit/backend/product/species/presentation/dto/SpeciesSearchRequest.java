package com.justcommit.backend.product.species.presentation.dto;

import com.justcommit.backend.common.request.PagingRequest;
import com.justcommit.backend.product.species.domain.enums.SpeciesCategory;
import org.springframework.data.domain.Sort;

public record SpeciesSearchRequest(
        String keyword,
        SpeciesCategory category,
        Integer page,
        Integer size
)implements PagingRequest {

    // 품종 선택 드롭다운이 전체 품종을 한 번에 받도록 한도를 크게 둔다
    private static final int SPECIES_PAGE_SIZE = 500;

    // TODO: 품종 목록을 페이지로 넘겨 보는 화면이 생기면 defaultSize를 공통 기본값(20)으로 되돌리고, 드롭다운만 size를 지정하도록 변경
    @Override
    public int defaultSize() {
        return SPECIES_PAGE_SIZE;
    }

    @Override
    public int maxSize() {
        return SPECIES_PAGE_SIZE;
    }

    @Override
    public Sort defaultSort() {
        return Sort.by(Sort.Direction.ASC, "name");
    }
}

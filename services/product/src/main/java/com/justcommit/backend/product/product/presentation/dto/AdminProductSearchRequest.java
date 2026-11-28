package com.justcommit.backend.product.product.presentation.dto;

import com.justcommit.backend.common.request.PagingRequest;
import com.justcommit.backend.product.ProductStatus;
import org.springframework.data.domain.Sort;

// 관리자 상품 목록 조건. keyword: 제목 포함, sellerId: 그 판매자 상품만, adminHidden: null 전체 / true 강제 숨김만 / false 강제 숨김 아닌 것만
// status: 상태 하나 (DELETED는 나오지 않음). 최신 등록 순
public record AdminProductSearchRequest(
        String keyword,
        Long sellerId,
        ProductStatus status,
        Boolean adminHidden,
        Integer page,
        Integer size
) implements PagingRequest {

    @Override
    public Sort defaultSort() {
        return Sort.by(Sort.Direction.DESC, "id");
    }
}

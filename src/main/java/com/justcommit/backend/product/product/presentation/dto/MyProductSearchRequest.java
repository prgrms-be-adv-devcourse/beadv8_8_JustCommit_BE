package com.justcommit.backend.product.product.presentation.dto;

import com.justcommit.backend.common.request.PagingRequest;
import com.justcommit.backend.product.ProductStatus;
import org.springframework.data.domain.Sort;


public record MyProductSearchRequest ( ProductStatus status,
                                      Integer page,
                                      Integer size)
implements PagingRequest {

    @Override
    public Sort defaultSort() {
        return Sort.by(Sort.Direction.DESC, "createdAt"); //최신순 정렬
    }
}

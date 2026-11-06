package com.justcommit.backend.market.order.presentation;

import com.justcommit.backend.common.request.PagingRequest;
import org.springframework.data.domain.Sort;

public record OrderListRequest(Integer page, Integer size) implements PagingRequest {
    @Override
    public Sort defaultSort() {
        return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    }
}

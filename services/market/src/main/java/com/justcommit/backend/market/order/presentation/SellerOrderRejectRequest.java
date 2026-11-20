package com.justcommit.backend.market.order.presentation;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

// 판매자 거절: 판매자 주문 › 상품 트리에서 체크한 주문상품 (#284). 거절 사유는 받지 않는다
public record SellerOrderRejectRequest(
        @NotEmpty List<@NotNull @Positive Long> orderItemIds
) {
}

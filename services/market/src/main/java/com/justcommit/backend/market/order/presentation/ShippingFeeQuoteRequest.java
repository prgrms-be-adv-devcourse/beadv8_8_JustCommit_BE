package com.justcommit.backend.market.order.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

// 배송비 미리 계산: 배송지 우편번호와 주문서에 담긴 판매자들 (?zipcode=63100&sellerIds=1,2)
public record ShippingFeeQuoteRequest(
        @NotBlank String zipcode,
        @NotEmpty List<Long> sellerIds
) {
}

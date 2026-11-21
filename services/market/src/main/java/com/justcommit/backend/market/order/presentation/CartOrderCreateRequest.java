package com.justcommit.backend.market.order.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

public record CartOrderCreateRequest(
        @NotEmpty List<@NotNull @Positive Long> cartItemIds,
        @NotBlank String recipientName,
        @NotBlank String recipientPhone,
        @NotBlank String zipcode,
        @NotBlank String address1,
        String address2,
        // 주문서에 보여 준 판매자 주문 1건의 배송비 (GET orders/shippingFees). 보내면 서버 계산과 비교하고, 없으면 비교하지 않는다
        @Positive BigDecimal expectedShippingFee
) {
}

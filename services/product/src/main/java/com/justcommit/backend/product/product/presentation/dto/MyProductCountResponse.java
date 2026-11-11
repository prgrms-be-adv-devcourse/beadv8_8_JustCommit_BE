package com.justcommit.backend.product.product.presentation.dto;

import com.justcommit.backend.product.ProductStatus;

import java.util.Map;

public record MyProductCountResponse(
        long total,
        long reviewPending,
        long rejected,
        long active,
        long paymentPending,
        long soldOut
) {
    public static MyProductCountResponse from(Map<ProductStatus, Long> counts) {
        long reviewPending = counts.getOrDefault(ProductStatus.REVIEW_PENDING, 0L);
        long rejected = counts.getOrDefault(ProductStatus.REJECTED, 0L);
        long active = counts.getOrDefault(ProductStatus.ACTIVE, 0L);
        long paymentPending = counts.getOrDefault(ProductStatus.PAYMENT_PENDING, 0L);
        long soldOut = counts.getOrDefault(ProductStatus.SOLD_OUT, 0L);
        long total = reviewPending + rejected + active + paymentPending + soldOut;
        return new MyProductCountResponse(total, reviewPending, rejected, active, paymentPending, soldOut);
    }
}
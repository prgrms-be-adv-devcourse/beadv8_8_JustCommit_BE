package com.justcommit.backend.market.client.product;

import java.math.BigDecimal;

// expectedPrice는 주문서 가격. product 현재 가격과 다르면 예약 전체가 409 PRICE_CHANGED
public record ReservationItem(Long productId, BigDecimal expectedPrice) {
}

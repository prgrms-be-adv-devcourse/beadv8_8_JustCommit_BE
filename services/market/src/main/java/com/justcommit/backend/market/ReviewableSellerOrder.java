package com.justcommit.backend.market;

import java.time.LocalDateTime;

// 리뷰를 쓸 수 있는 후보 판매자 주문 (#585 작성 가능한 주문 목록). 구매자 본인 · 구매 확정 시각 있음 · 구매 확정 주문상품 있음까지 거른 값.
// 리뷰를 이미 썼는지는 review가 거른다
public record ReviewableSellerOrder(
  Long sellerOrderId,
  Long orderId,
  Long sellerId,
  LocalDateTime orderedAt,
  LocalDateTime confirmedAt
) {
}

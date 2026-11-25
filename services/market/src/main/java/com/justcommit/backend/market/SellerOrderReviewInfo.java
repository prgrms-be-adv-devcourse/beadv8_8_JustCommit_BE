package com.justcommit.backend.market;

import java.time.LocalDateTime;

// 리뷰 작성 판단에 쓰는 판매자 주문 정보 (#582). 작성 가능 여부 · 기간 판단은 review가 한다.
// confirmedAt: 구매 확정 전이면 null. 전부 반품돼도 채워지므로 hasConfirmedItem(구매 확정 주문상품이 있는지)을 함께 본다
public record SellerOrderReviewInfo(
  Long sellerOrderId,
  Long orderId,
  Long buyerId,
  Long sellerId,
  LocalDateTime confirmedAt,
  boolean hasConfirmedItem
) {
}

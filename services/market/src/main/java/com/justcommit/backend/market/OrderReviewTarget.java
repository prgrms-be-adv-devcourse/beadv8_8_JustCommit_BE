package com.justcommit.backend.market;

import java.time.LocalDateTime;

// 주문 상세의 판매자별 리뷰 상태 판정에 쓰는 판매자 주문 정보 (#644). 구매자 본인의 주문에서만 만든다.
// hasConfirmedItem: 구매 확정(CONFIRMED) 주문상품이 있음. allItemsCancelled: 주문상품이 모두 취소 완료(CANCELLED)
// (취소 중 · 환불 확인 필요 등 처리 중인 상품이 있으면 false). 전부 취소된 판매자 주문은 confirmedAt이 채워지지 않는다
public record OrderReviewTarget(
  Long sellerOrderId,
  Long sellerId,
  LocalDateTime confirmedAt,
  boolean hasConfirmedItem,
  boolean allItemsCancelled
) {
}

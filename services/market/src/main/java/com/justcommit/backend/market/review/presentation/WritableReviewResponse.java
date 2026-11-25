package com.justcommit.backend.market.review.presentation;

import java.time.LocalDateTime;
import java.util.List;

// 작성 가능한 주문 하나 (#585). writableUntil(구매 확정 시각 + 72시간)부터는 쓸 수 없다.
// 판매자 닉네임은 회원 복제본 기준(탈퇴면 「탈퇴한 회원」, 복제본이 없으면 「알 수 없는 회원」), 구매 상품명은 구매 확정된 주문상품만
public record WritableReviewResponse(
  Long sellerOrderId,
  Long orderId,
  Long sellerId,
  String sellerNickname,
  LocalDateTime orderedAt,
  LocalDateTime confirmedAt,
  LocalDateTime writableUntil,
  List<String> productNames
) {
}

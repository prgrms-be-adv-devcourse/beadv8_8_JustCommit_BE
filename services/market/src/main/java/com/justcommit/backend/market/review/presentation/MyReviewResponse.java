package com.justcommit.backend.market.review.presentation;

import com.justcommit.backend.market.review.domain.ReviewTag;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

// 내 리뷰 목록의 리뷰 하나 (#584). 판매자 닉네임은 회원 복제본 기준(탈퇴면 「탈퇴한 회원」, 복제본이 없으면 「알 수 없는 회원」),
// 구매 상품명은 구매 확정된 주문상품만, 태그는 enum 순서. deletableUntil(작성 시각 + 72시간)부터는 지울 수 없다
public record MyReviewResponse(
  Long reviewId,
  Long sellerId,
  String sellerNickname,
  Long orderId,
  LocalDateTime orderedAt,
  BigDecimal rating,
  String content,
  List<ReviewTag> tags,
  List<String> productNames,
  LocalDateTime createdAt,
  LocalDateTime deletableUntil
) {
}

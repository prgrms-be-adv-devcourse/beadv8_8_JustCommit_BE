package com.justcommit.backend.market.review.presentation;

import com.justcommit.backend.market.review.domain.ReviewTag;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

// 주문 상세의 판매자별 리뷰 상태 (#644). 판매자 주문은 sellerOrderId 순
public record OrderReviewStatusResponse(
  Long orderId,
  Summary summary,
  List<SellerOrderReview> sellerOrders
) {

  // targetCount: WRITABLE · WRITTEN · DELETED · EXPIRED (구매 확정 상품이 있었던 판매자 주문)
  // writtenCount: WRITTEN · DELETED (리뷰를 한 번 등록함). writableCount: WRITABLE
  // nextWritable: WRITABLE 중 마감이 가장 가까운 것 (같으면 sellerOrderId 순), 없으면 null
  public record Summary(
    int targetCount,
    int writtenCount,
    int writableCount,
    NextWritable nextWritable
  ) {
  }

  public record NextWritable(
    Long sellerOrderId,
    Long sellerId,
    String sellerNickname,
    LocalDateTime writableUntil
  ) {
  }

  // writableUntil: 구매 확정 시각 + 72시간 (확정 전이면 null). review: WRITTEN일 때만, 그 밖(DELETED 포함)은 null
  public record SellerOrderReview(
    Long sellerOrderId,
    Long sellerId,
    String sellerNickname,
    List<String> productNames,
    LocalDateTime confirmedAt,
    LocalDateTime writableUntil,
    SellerOrderReviewStatus reviewStatus,
    WrittenReview review
  ) {
  }

  public record WrittenReview(
    Long reviewId,
    BigDecimal rating,
    String content,
    List<ReviewTag> tags,
    LocalDateTime createdAt
  ) {
  }
}

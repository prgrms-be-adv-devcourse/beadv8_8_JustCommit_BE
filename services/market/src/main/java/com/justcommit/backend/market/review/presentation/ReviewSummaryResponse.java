package com.justcommit.backend.market.review.presentation;

import com.justcommit.backend.market.review.domain.ReviewTag;

import java.math.BigDecimal;
import java.util.List;

// 판매자 리뷰 집계 (#583). 삭제한 리뷰는 넣지 않는다.
// averageRating: 소수 첫째 자리 HALF_UP, 리뷰가 없으면 null. ratingCounts: 5.0 → 0.5 10구간(합 = reviewCount).
// tagCounts: ReviewTag enum 순서 5종. 없는 구간 · 태그는 0
public record ReviewSummaryResponse(
  BigDecimal averageRating,
  long reviewCount,
  List<RatingCount> ratingCounts,
  List<TagCount> tagCounts
) {

  public record RatingCount(
    BigDecimal rating,
    long count
  ) {
  }

  public record TagCount(
    ReviewTag tag,
    long count
  ) {
  }
}

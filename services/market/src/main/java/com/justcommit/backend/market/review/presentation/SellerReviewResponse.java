package com.justcommit.backend.market.review.presentation;

import com.justcommit.backend.market.review.domain.ReviewTag;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

// 판매자 리뷰 목록의 리뷰 하나 (#583). 태그는 enum 순서, 구매 상품명은 구매 확정된 주문상품만(주문상품 순서).
// 작성자 닉네임은 회원 복제본 기준: 탈퇴면 「탈퇴한 회원」, 복제본이 없으면 「알 수 없는 회원」
public record SellerReviewResponse(
  Long reviewId,
  BigDecimal rating,
  String content,
  List<ReviewTag> tags,
  LocalDateTime createdAt,
  String writerNickname,
  List<String> productNames
) {
}

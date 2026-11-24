package com.justcommit.backend.market.review.presentation;

import com.justcommit.backend.common.request.PagingRequest;
import org.springframework.data.domain.Sort;

// 판매자 리뷰 목록 페이지 (#583). 최신순, 같은 시각이면 나중에 저장한 리뷰가 먼저
public record SellerReviewListRequest(
  Integer page,
  Integer size
) implements PagingRequest {

  @Override
  public Sort defaultSort() {
    return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
  }
}

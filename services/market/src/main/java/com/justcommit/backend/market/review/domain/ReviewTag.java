package com.justcommit.backend.market.review.domain;

// 리뷰 태그 5종 (#582). 리뷰 하나에 0개 이상, 같은 태그는 한 번만. 판매자 상점에서 태그별 개수를 센다 (#583)
public enum ReviewTag {
  AS_DESCRIBED,
  DETAILED_DESCRIPTION,
  CAREFUL_PACKING,
  HEALTHY_ARRIVAL,
  GOOD_PRICE
}

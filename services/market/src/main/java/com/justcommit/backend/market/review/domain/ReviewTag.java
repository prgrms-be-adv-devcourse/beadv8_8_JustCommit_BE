package com.justcommit.backend.market.review.domain;

import com.fasterxml.jackson.annotation.JsonCreator;

// 리뷰 태그 5종 (#582). 리뷰 하나에 0개 이상, 같은 태그는 한 번만. 판매자 상점에서 태그별 개수를 센다 (#583)
public enum ReviewTag {
  AS_DESCRIBED,
  DETAILED_DESCRIPTION,
  CAREFUL_PACKING,
  HEALTHY_ARRIVAL,
  GOOD_PRICE;

  // 요청 JSON에서는 태그 이름 문자열만 받는다. Jackson 기본 설정은 숫자(0)와 숫자 문자열("0")을 순번으로 바꿔 받으므로
  // 이름으로만 찾게 한다. 없는 이름이면 예외가 나서 JSON을 읽지 못한 400(BAD_REQUEST_DATA)이 된다
  @JsonCreator
  public static ReviewTag from(String name) {
    return valueOf(name);
  }
}

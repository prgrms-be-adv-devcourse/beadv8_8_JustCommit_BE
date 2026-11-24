package com.justcommit.backend.market.review.presentation;

import com.justcommit.backend.market.review.domain.ReviewTag;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

// 리뷰 작성 요청 (#582). 값이 있는지만 본다. 별점 범위 · 0.5 단위, 글 길이, 태그 null · 중복은 Review가 검사한다
public record ReviewCreateRequest(
  @NotNull Long sellerOrderId,
  @NotNull BigDecimal rating,
  @NotNull String content,
  List<ReviewTag> tags
) {

  // 태그 생략 · null은 빈 목록으로 바꾼다. 목록 안의 null · 중복은 그대로 넘겨 Review가 400으로 거절한다
  // (List.copyOf는 목록 안 null에서 NPE를 내므로 쓰지 않는다)
  public ReviewCreateRequest {
    tags = tags == null ? List.of() : tags;
  }
}

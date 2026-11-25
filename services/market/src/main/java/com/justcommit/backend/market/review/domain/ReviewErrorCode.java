package com.justcommit.backend.market.review.domain;

import com.justcommit.backend.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ReviewErrorCode implements ErrorCode {
  // 없는 판매자 주문과 남의 판매자 주문을 구분하지 않는다 (남의 주문이 있는지 알리지 않음)
  REVIEW_SELLER_ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "리뷰를 쓸 판매자 주문을 찾을 수 없습니다."),
  REVIEW_NOT_CONFIRMED(HttpStatus.CONFLICT, "구매 확정 후에 리뷰를 쓸 수 있습니다."),
  // 전부 반품: 판매자 주문이 끝나 구매 확정 시각은 있지만 구매 확정된 주문상품이 없다
  REVIEW_NO_CONFIRMED_ITEM(HttpStatus.CONFLICT, "구매 확정된 상품이 없어 리뷰를 쓸 수 없습니다."),
  // 삭제한 리뷰가 있는 판매자 주문도 포함
  REVIEW_ALREADY_WRITTEN(HttpStatus.CONFLICT, "이미 리뷰를 쓴 주문입니다."),
  REVIEW_PERIOD_EXPIRED(HttpStatus.CONFLICT, "리뷰 작성 기간이 지났습니다."),
  // 없는 리뷰 · 다른 작성자의 리뷰 · 이미 삭제된 리뷰를 구분하지 않는다 (#584)
  REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, "리뷰를 찾을 수 없습니다."),
  REVIEW_DELETE_PERIOD_EXPIRED(HttpStatus.CONFLICT, "리뷰 삭제 기간이 지났습니다.");

  private final HttpStatus status;
  private final String message;
}

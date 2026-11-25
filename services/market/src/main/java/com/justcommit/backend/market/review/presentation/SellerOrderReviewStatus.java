package com.justcommit.backend.market.review.presentation;

// 주문 상세에서 판매자 주문 하나의 리뷰 상태 (#644). 위에서부터 처음 맞는 것 하나를 쓴다
public enum SellerOrderReviewStatus {
  // 리뷰를 썼다가 삭제함 (다시 쓸 수 없음, 리뷰 내용은 내보내지 않음)
  DELETED,
  // 리뷰를 씀
  WRITTEN,
  // 구매 확정된 주문상품 없이 끝남: 전부 반품(구매 확정 시각 있음) 또는 전부 취소 완료(구매 확정 시각 없음)
  NO_CONFIRMED_ITEM,
  // 아직 구매 확정 전 (취소 · 환불이 처리 중인 상품이 있어도 여기)
  NOT_CONFIRMED,
  // 구매 확정 시각부터 72시간이 지남
  EXPIRED,
  // 지금 쓸 수 있음
  WRITABLE
}

package com.justcommit.backend.market.client.product;

import java.util.List;

// product에 직접 부르는 건 상품 예약 하나만 남는다. 판매 완료 · 예약 해제 · 판매 재개는 Outbox 이벤트 (ADR 0016, #277)

public interface ProductUseCase {

  // 전부 아니면 전무. 가격이 다르면 PRICE_CHANGED, 예약할 수 없으면 PRODUCT_UNAVAILABLE,
  // 응답 타임아웃이면 InternalApiResultUnknownException (다시 부르지 않고 주문 실패)
  void reserve(Long orderId, List<ReservationItem> items);
}

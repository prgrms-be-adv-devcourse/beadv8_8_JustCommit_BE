package com.justcommit.backend.market.client.product;

import java.util.List;

public interface ProductUseCase {

    // 전부 아니면 전무. 가격이 다르면 PRICE_CHANGED, 예약할 수 없으면 PRODUCT_UNAVAILABLE,
    // 응답 타임아웃이면 InternalApiResultUnknownException (다시 부르지 않고 주문 실패)
    void reserve(Long orderId, List<ReservationItem> items);

    boolean release(Long orderId);

    // false면 예약이 이미 풀렸거나 다른 주문에 팔린 상태라 호출한 쪽이 후속 처리
    boolean markSoldOut(Long orderId);

    boolean releaseItem(Long productId);
}

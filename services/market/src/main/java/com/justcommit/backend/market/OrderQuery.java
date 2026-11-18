package com.justcommit.backend.market;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderQuery {

    OrderResult getOrder(Long orderId);

    SellerOrderResult getSellerOrder(Long sellerOrderId);

    List<SellerOrderResult> getSellerOrderBySellerId(Long sellerId);

    // 배송이 택배 예약·알림·정산 이벤트에 쓰는 주문 정보 (판매자 주문 단위)
    OrderShippingInfo getShippingInfo(Long sellerOrderId);

    // 장바구니 추천용: since 이후 구매 확정된 상품 ID, 최신순 최대 limit개 (기준 시각은 판매자 주문의 confirmedAt)
    List<Long> getRecentConfirmedProductIds(Long buyerId, LocalDateTime since, int limit);

}

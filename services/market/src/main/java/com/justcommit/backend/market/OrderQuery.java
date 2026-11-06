package com.justcommit.backend.market;

import java.util.List;

public interface OrderQuery {

    OrderResult getOrder(Long orderId);

    SellerOrderResult getSellerOrder(Long sellerOrderId);

    List<SellerOrderResult> getSellerOrderBySellerId(Long sellerId);

    // 배송이 택배 예약·알림·정산 이벤트에 쓰는 주문 정보 (판매자 주문 단위)
    OrderShippingInfo getShippingInfo(Long sellerOrderId);

}

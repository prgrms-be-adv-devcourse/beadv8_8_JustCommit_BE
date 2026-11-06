package com.justcommit.backend.market;

import java.math.BigDecimal;

// 배송이 택배 예약·알림·정산 이벤트에 쓰는 주문 정보. 배송은 판매자 주문 단위라 seller_order_id로 조회한다.
public record OrderShippingInfo(
        Long orderId,
        Long buyerId,
        Long sellerId,
        BigDecimal shippingFee,
        String recipientName,
        String recipientPhone,
        String address1,
        String address2
) {
}

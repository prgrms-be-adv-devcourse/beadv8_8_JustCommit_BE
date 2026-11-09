package com.justcommit.backend.notification.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum NotificationType {
    // 판매자
    SHIPMENT_BOOKED("택배 예약이 완료됐어요. 기사님이 방문할 예정이니 상품을 준비해 주세요."),
    SELLER_ORDER_PAID("새 주문이 들어왔어요."),
    SELLER_ORDER_CANCELLED("판매자 주문이 취소됐어요."),
    PURCHASE_CONFIRMED("구매자가 구매를 확정했어요."),
    SELLER_SHIPMENT_PICKED_UP("택배 수거가 완료됐어요. 이제 구매자가 주문을 취소할 수 없어요."),
    SELLER_SHIPMENT_IN_TRANSIT("상품이 구매자에게 배송 중이에요."),
    SELLER_SHIPMENT_DELIVERED("배송이 완료됐어요. 구매 확정 후 정산이 진행되며, 3일 뒤 자동으로 확정돼요."),

    // 구매자
    SHIPMENT_PICKED_UP("택배 수거가 완료됐어요. 상품 확인 후 배송이 시작되며, 이제 주문을 취소할 수 없어요."),
    SHIPMENT_IN_TRANSIT("상품이 배송 중이에요."),
    SHIPMENT_DELIVERED("배송이 완료됐어요."),
    SELLER_ORDER_CANCELLED_BUYER("주문이 취소됐어요."),
    ORDER_ITEM_AUTO_CONFIRMED("상품의 구매가 자동 확정됐어요.");

    private final String message;
}

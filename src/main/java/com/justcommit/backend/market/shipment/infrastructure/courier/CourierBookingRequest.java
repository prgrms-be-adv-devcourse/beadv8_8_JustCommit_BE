package com.justcommit.backend.market.shipment.infrastructure.courier;

public record CourierBookingRequest(
        Party sender,        // 판매자
        Party receiver,      // 구매자 (주문 때 저장한 배송지)
        String itemSummary   // 예: "몬스테라 외 1건"
) {
    public record Party(String name, String phone, String address) {
    }
}

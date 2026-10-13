package com.justcommit.backend.market.shipment.application;

public record ShipmentBookingCommand(
        Long sellerOrderId,
        Long sellerId,           // 판매자 주소를 조회할 때 씀
        String receiverName,
        String receiverPhone,
        String receiverAddress,  // 주소1 + 주소2를 합친 한 줄
        String itemSummary       // "몬스테라 외 1건"
) {
}
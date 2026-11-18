package com.justcommit.backend.market.shipment.infrastructure.courier;

// 택배사 배송 조회 응답 중 market이 쓰는 칸만 (나머지 칸은 무시)
public record CourierShipmentView(
        String trackingNo,
        String courierOrderNo,
        String status            // 택배사 용어 그대로
) {
}

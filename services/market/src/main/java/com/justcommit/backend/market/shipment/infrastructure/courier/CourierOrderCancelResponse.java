package com.justcommit.backend.market.shipment.infrastructure.courier;

// 택배사 주문번호 취소 응답 (200). 같은 주문번호를 다시 보내면 첫 판정을 그대로 돌려준다.
public record CourierOrderCancelResponse(
        String courierOrderNo,
        String trackingNo,       // 예약 전에 취소된 주문번호면 null
        String status,           // 택배사 용어 그대로: CANCELLED · RETURNING · RETURNED
        boolean returning        // true면 택배사 장애로 이미 수거된 뒤 취소가 인정돼 택배사가 반송 (택배사 비용)
) {
}

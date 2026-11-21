package com.justcommit.backend.market.shipment.infrastructure.courier;

// 택배사 배송 조회 응답 중 market이 쓰는 칸만 (나머지 칸은 무시)
public record CourierShipmentView(
        String trackingNo,
        String courierOrderNo,
        String status,                    // 택배사 용어 그대로 (예: NEAR_5KM)
        Integer remainingDistanceMeters,  // 지금 이동 중인 구간의 남은 거리(m). 수거 전이면 기사 → 판매자. 머물거나 끝나면 null
        String locationName               // 지금 단계의 우체국 지점 이름. 모르면 null
) {
}

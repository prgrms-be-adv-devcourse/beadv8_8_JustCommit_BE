package com.justcommit.backend.market.order.presentation;

import com.justcommit.backend.market.ShipmentTracking;

import java.time.LocalDateTime;

// 배송 조회 응답 (#588). 단계별 시각은 market 값(아직 안 된 단계는 null).
// courierStatus · remainingDistanceMeters · locationName · checkedAt은 택배사 값이고, 택배사에 묻지 않았거나 실패하면 null.
// 남은 거리는 안내용: 취소 가능 여부는 구매자가 누른 시각에 택배사가 판정한다 (ADR 0026)
public record ShipmentTrackingResponse(
        Long sellerOrderId,
        String status,                    // market 배송 상태 (READY · BOOKED · CANCEL_LOCKED · PICKED_UP · IN_TRANSIT · DELIVERED · CANCEL_PENDING · CANCELLED)
        String trackingNo,
        LocalDateTime bookedAt,
        LocalDateTime pickedUpAt,
        LocalDateTime inTransitAt,
        LocalDateTime deliveredAt,
        LocalDateTime cancelledAt,
        String courierStatus,             // 택배사 세부 단계 (예: NEAR_5KM, ARRIVED_ORIGIN, OUT_FOR_DELIVERY)
        // 지금 이동 중인 구간의 남은 거리(km, 택배사 m ÷ 1000, 반올림 없음). 수거 전이면 기사님 → 판매자(수거지),
        // 수거 뒤에는 간선 · 배송 구간. 지점 대기 중이면 null. 이름은 FE가 읽는 칸에 맞춤
        Double driverDistanceKm,
        String locationName,              // 지금 단계의 우체국 지점 이름
        LocalDateTime driverCheckedAt     // 택배사에서 받은 시각
) {
    public static ShipmentTrackingResponse of(Long sellerOrderId, ShipmentTracking tracking) {
        Integer meters = tracking.remainingDistanceMeters();
        return new ShipmentTrackingResponse(sellerOrderId, tracking.status(), tracking.trackingNo(),
                tracking.bookedAt(), tracking.pickedUpAt(), tracking.inTransitAt(), tracking.deliveredAt(),
                tracking.cancelledAt(), tracking.courierStatus(), meters == null ? null : meters / 1000.0,
                tracking.locationName(), tracking.checkedAt());
    }
}

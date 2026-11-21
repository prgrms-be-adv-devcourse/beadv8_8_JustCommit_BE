package com.justcommit.backend.market.order.presentation;

import com.justcommit.backend.market.ShipmentTracking;

import java.time.LocalDateTime;

// 반품 택배 조회 응답 (#617). 칸은 가는 배송 조회(ShipmentTrackingResponse)와 같고, 반품은 주문상품 단위라 orderItemId를 둔다.
// 단계별 시각은 market 값(아직 안 된 단계는 null), courierStatus · driverDistanceKm · locationName · driverCheckedAt은
// 택배사 값이고 택배사에 묻지 않았거나 실패하면 null
public record ReturnShipmentTrackingResponse(
        Long orderItemId,
        String status,                    // market 반품 배송 상태 (READY · BOOKED · CANCEL_LOCKED · PICKED_UP · IN_TRANSIT · DELIVERED)
        String trackingNo,
        LocalDateTime bookedAt,
        LocalDateTime pickedUpAt,
        LocalDateTime inTransitAt,
        LocalDateTime deliveredAt,        // 판매자에게 도착한 시각
        LocalDateTime cancelledAt,        // 반품 택배는 취소하지 않으므로 늘 null. 가는 배송 응답과 칸을 맞추려고 둔다
        String courierStatus,
        // 지금 이동 중인 구간의 남은 거리(km, 택배사 m ÷ 1000). 반품은 보내는 쪽이 구매자라 수거 전이면 기사님 → 구매자,
        // 수거 뒤에는 간선 · 배송(→ 판매자) 구간. 지점 대기 중이면 null
        Double driverDistanceKm,
        String locationName,
        LocalDateTime driverCheckedAt
) {
    public static ReturnShipmentTrackingResponse of(Long orderItemId, ShipmentTracking tracking) {
        Integer meters = tracking.remainingDistanceMeters();
        return new ReturnShipmentTrackingResponse(orderItemId, tracking.status(), tracking.trackingNo(),
                tracking.bookedAt(), tracking.pickedUpAt(), tracking.inTransitAt(), tracking.deliveredAt(),
                tracking.cancelledAt(), tracking.courierStatus(), meters == null ? null : meters / 1000.0,
                tracking.locationName(), tracking.checkedAt());
    }
}

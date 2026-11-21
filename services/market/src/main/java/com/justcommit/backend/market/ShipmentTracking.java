package com.justcommit.backend.market;

import java.time.LocalDateTime;

// 화면용 배송 조회 결과 (#588). 단계별 시각은 market이 가진 값(아직 안 된 단계는 null),
// courierStatus · remainingDistanceMeters · locationName · checkedAt은 택배사에서 받은 값이고 조회하지 않았거나 실패하면 null
public record ShipmentTracking(
        String status,
        String trackingNo,
        LocalDateTime bookedAt,
        LocalDateTime pickedUpAt,
        LocalDateTime inTransitAt,
        LocalDateTime deliveredAt,
        LocalDateTime cancelledAt,
        String courierStatus,
        Integer remainingDistanceMeters,
        String locationName,
        LocalDateTime checkedAt
) {
}

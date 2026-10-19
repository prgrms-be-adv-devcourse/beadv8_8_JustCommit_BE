package com.justcommit.backend.market;

import java.time.LocalDateTime;

// 배송 단계별 시각은 아직 안 된 단계면 null
public record ShipmentResult(Long shipmentId, String status, LocalDateTime deliveredAt, String trackingNo,
                             LocalDateTime bookedAt, LocalDateTime pickedUpAt, LocalDateTime inTransitAt) {
    public ShipmentResult(Long shipmentId, String status, LocalDateTime deliveredAt, String trackingNo) {
        this(shipmentId, status, deliveredAt, trackingNo, null, null, null);
    }

    public ShipmentResult(Long shipmentId, String status, LocalDateTime deliveredAt) {
        this(shipmentId, status, deliveredAt, null);
    }
}

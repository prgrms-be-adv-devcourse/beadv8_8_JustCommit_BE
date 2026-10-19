package com.justcommit.backend.market;

import java.time.LocalDateTime;

public record ShipmentResult(Long shipmentId, String status, LocalDateTime deliveredAt, String trackingNo) {
    public ShipmentResult(Long shipmentId, String status, LocalDateTime deliveredAt) {
        this(shipmentId, status, deliveredAt, null);
    }
}

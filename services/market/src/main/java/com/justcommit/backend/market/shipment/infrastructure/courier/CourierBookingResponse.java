package com.justcommit.backend.market.shipment.infrastructure.courier;

import java.time.OffsetDateTime;

public record CourierBookingResponse(
        String trackingNo,        // 송장번호 404000000001
        String courierOrderNo,    // 우리가 보낸 shipment-20261012-000045
        String status,            // 택배사 용어 그대로: RESERVED
        OffsetDateTime bookedAt   // 2026-10-12T10:00:00+09:00
) {
}

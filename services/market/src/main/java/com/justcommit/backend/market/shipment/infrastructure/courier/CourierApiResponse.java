package com.justcommit.backend.market.shipment.infrastructure.courier;

public record CourierApiResponse<T>(
        int status,
        String code,       // 실패할 때만 옴. 예: IDEMPOTENCY_KEY_REUSED
        String message,
        T data             // 성공하면 여기에 CourierBookingResponse
) {
}
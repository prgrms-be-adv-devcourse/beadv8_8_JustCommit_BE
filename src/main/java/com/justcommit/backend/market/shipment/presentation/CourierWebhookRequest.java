package com.justcommit.backend.market.shipment.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

public record CourierWebhookRequest(
        @NotBlank String eventId,           // 로그 추적용
        @NotBlank String courierOrderNo,    // 배송을 찾는 키
        @NotBlank String trackingNo,
        @NotBlank String status,            // 택배사 용어: PICKED_UP / IN_TRANSIT / DELIVERED
        @NotNull OffsetDateTime occurredAt  // 택배사에서 실제로 일어난 시각 (+09:00)
) {
}

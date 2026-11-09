
package com.justcommit.backend.settlement.infrastructure.messaging;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ShipmentPickedUpEvent(
        Long sellerOrderId,
        Long sellerId,
        BigDecimal shippingFee,
        LocalDateTime pickedUpAt
) {
}

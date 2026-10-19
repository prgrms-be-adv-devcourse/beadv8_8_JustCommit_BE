package com.justcommit.backend.common.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ShipmentPickedUpEvent(Long sellerOrderId, Long sellerId, BigDecimal shippingFee,
                                   LocalDateTime pickedUpAt) {
}

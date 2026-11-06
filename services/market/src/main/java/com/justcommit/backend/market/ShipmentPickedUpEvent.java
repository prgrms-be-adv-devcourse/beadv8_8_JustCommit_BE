package com.justcommit.backend.market;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ShipmentPickedUpEvent(Long sellerOrderId, Long sellerId, BigDecimal shippingFee,
                                   LocalDateTime pickedUpAt) {
}

package com.justcommit.backend.market;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PurchaseConfirmedEvent(Long sellerOrderId, Long sellerId, BigDecimal saleAmount,
                                     LocalDateTime confirmedAt, String itemSummary) implements IntegrationEvent {

    @Override
    public String topic() {
        return KafkaTopics.MARKET_EVENTS;
    }

    @Override
    public String eventType() {
        return "PurchaseConfirmed";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(sellerOrderId);
    }
}

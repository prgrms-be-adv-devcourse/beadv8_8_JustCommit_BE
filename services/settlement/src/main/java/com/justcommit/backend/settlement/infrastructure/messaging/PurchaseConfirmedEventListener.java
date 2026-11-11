package com.justcommit.backend.settlement.infrastructure.messaging;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.settlement.application.SettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseConfirmedEventListener {

    private static final String GROUP_ID = "settlement";

    private final EventEnvelopeMapper envelopeMapper;
    private final SettlementService settlementService;

    @KafkaListener(topics = "${custom.kafka.topics.market-events}", groupId = GROUP_ID)
    public void on(EventEnvelope envelope) {
        if (!"PurchaseConfirmed".equals(envelope.eventType())) {
            log.debug("settlement이 쓰지 않는 market 이벤트라 건너뜀: eventType={}, eventId={}",
                    envelope.eventType(), envelope.eventId());
            return;
        }
        PurchaseConfirmedEvent event = envelopeMapper.readPayload(envelope, PurchaseConfirmedEvent.class);
        settlementService.settle(event.sellerOrderId(), event.sellerId(), event.saleAmount(), event.itemSummary());
    }
}

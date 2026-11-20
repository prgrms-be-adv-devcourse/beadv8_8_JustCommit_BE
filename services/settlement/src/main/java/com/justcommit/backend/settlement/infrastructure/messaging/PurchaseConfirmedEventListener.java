package com.justcommit.backend.settlement.infrastructure.messaging;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.settlement.application.SettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// 구매 확정 → 정산 생성. 같은 판매자 주문이 여러 번 와도 정산 UK(sellerOrderId)로 1건만 만든다
@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseConfirmedEventListener {

    private final EventEnvelopeMapper envelopeMapper;
    private final SettlementService settlementService;

    // market.events.v1: 실패하면 공통 설정이 1 · 2 · 4초 재시도 후 market.events.v1.DLT로 보낸다
    @KafkaListener(topics = KafkaTopics.MARKET_EVENTS)
    public void on(EventEnvelope envelope) {
        switch (envelope.eventType()) {
            case "PurchaseConfirmed" -> settle(envelopeMapper.readPayload(envelope, PurchaseConfirmedEvent.class));
            // 알림용 이벤트(ShipmentStatusChanged 등)는 정산과 상관없어 건너뜀
            default -> log.debug("settlement가 쓰지 않는 market 이벤트라 건너뜀: eventType={}, eventId={}",
                    envelope.eventType(), envelope.eventId());
        }
    }

    private void settle(PurchaseConfirmedEvent event) {
        settlementService.settle(
                event.sellerOrderId(),
                event.sellerId(),
                event.saleAmount(),
                event.shippingFee(),
                event.sellerBurdenAmount(),
                event.itemSummary()
        );
    }
}

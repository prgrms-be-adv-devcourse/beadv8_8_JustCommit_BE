package com.justcommit.backend.settlement.infrastructure.messaging;

import com.justcommit.backend.market.PurchaseConfirmedEvent;
import com.justcommit.backend.settlement.application.SettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PurchaseConfirmedEventListener {

    private final SettlementService settlementService;

    @KafkaListener(
            topics = "${custom.kafka.topics.purchase-confirmed}",
            groupId = "settlement"
    )
    public void onPurchaseConfirmed(PurchaseConfirmedEvent event) {
        settlementService.settle(
                event.sellerOrderId(),
                event.sellerId(),
                event.saleAmount()
        );
    }
}

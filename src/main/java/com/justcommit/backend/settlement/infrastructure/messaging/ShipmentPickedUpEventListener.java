package com.justcommit.backend.settlement.infrastructure.messaging;

import com.justcommit.backend.common.event.ShipmentPickedUpEvent;
import com.justcommit.backend.payment.PaymentUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// 택배가 수거되면(더 이상 취소 불가) 배송비를 HOLDING에서 REVENUE로 옮긴다.
// 이벤트를 payment가 직접 받으면 payment -> market 의존이 생겨 market -> payment와 순환하므로 정산이 받아 넘긴다.
@Component
@RequiredArgsConstructor
public class ShipmentPickedUpEventListener {

    private final PaymentUseCase paymentUseCase;

    @KafkaListener(
            topics = "${custom.kafka.topics.shipment-picked-up}",
            groupId = "settlement"
    )
    public void onShipmentPickedUp(ShipmentPickedUpEvent event) {
        // 중복 수신은 collectShippingFee가 sellerOrderId로 걸러낸다
        paymentUseCase.collectShippingFee(event.sellerOrderId(), event.shippingFee());
    }
}

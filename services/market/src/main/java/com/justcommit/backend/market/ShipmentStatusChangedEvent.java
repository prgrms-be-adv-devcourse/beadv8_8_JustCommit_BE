package com.justcommit.backend.market;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.time.LocalDateTime;
import java.util.Objects;

// 배송 상태가 바뀌면(예약·수거·배송 중·완료) 알림으로 보내는 이벤트 (market.events.v1, notification)
// status는 BOOKED, PICKED_UP, IN_TRANSIT, DELIVERED 중 하나
// itemSummary는 결제 시점의 상품 요약 (예: "몬스테라 외 1건"), 알림에서 어떤 주문인지 구분하는 데 쓴다
public record ShipmentStatusChangedEvent(Long shipmentId, Long orderId, Long sellerOrderId, Long buyerId, Long sellerId,
                                         String status, LocalDateTime occurredAt, String itemSummary)
        implements IntegrationEvent {

    public ShipmentStatusChangedEvent {
        Objects.requireNonNull(sellerOrderId, "sellerOrderId");
    }

    @Override
    public String topic() {
        return KafkaTopics.MARKET_EVENTS;
    }

    @Override
    public String eventType() {
        return "ShipmentStatusChanged";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(sellerOrderId);
    }
}

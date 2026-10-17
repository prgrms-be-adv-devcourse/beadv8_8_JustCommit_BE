package com.justcommit.backend.market;

import java.time.LocalDateTime;

// 배송 상태가 바뀌면(예약·수거·배송 중·완료) 알림으로 보내는 이벤트
// status는 BOOKED, PICKED_UP, IN_TRANSIT, DELIVERED 중 하나
public record ShipmentStatusChangedEvent(Long shipmentId, Long sellerOrderId, Long buyerId, Long sellerId,
                                         String status, LocalDateTime occurredAt) {
}

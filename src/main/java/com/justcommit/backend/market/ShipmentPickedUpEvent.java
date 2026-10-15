package com.justcommit.backend.market;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 택배가 수거되면(더 이상 취소 불가) 택배비를 매출로 잡도록 정산에 보내는 이벤트
public record ShipmentPickedUpEvent(Long sellerOrderId, Long sellerId, BigDecimal shippingFee, LocalDateTime pickedUpAt) {
}

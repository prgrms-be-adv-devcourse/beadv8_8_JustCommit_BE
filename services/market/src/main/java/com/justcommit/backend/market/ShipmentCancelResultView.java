package com.justcommit.backend.market;

// 주문 Saga가 읽는 택배 취소 결과 (ADR 0026). cancelled면 환불, 아니면(취소 불가) 주문상품을 되돌린다
// courierReturn: 이미 수거돼 택배사가 판매자에게 반송하는 취소
public record ShipmentCancelResultView(Long id, Long sellerOrderId, boolean cancelled, boolean courierReturn) {
}

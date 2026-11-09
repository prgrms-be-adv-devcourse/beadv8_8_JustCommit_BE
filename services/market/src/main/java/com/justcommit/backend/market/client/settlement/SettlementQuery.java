package com.justcommit.backend.market.client.settlement;

public interface SettlementQuery {

    // 연결 실패 · 응답 타임아웃 · 5xx면 예외 대신 UNKNOWN을 돌려준다 (판매자 주문 상세는 정산 칸만 비우고 보여 줌)
    SellerOrderSettlement getSettlement(Long sellerOrderId);
}

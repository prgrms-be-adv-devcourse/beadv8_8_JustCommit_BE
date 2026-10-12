package com.justcommit.backend.settlement;

import java.math.BigDecimal;

public interface SettlementUseCase {
    /**
     * 판매자 주문 1건을 정산한다. (판매액에서 수수료를 뺀 금액을 지갑에 입금)
     * 같은 sellerOrderId로 여러 번 호출해도 정산은 1건만 생성되며 (seller_order_id UK),
     * 이미 정산된 경우 기존 정산 결과를 반환한다.
     *
     *
     * @param sellerOrderId 판매자 주문 ID
     * @param sellerId      판매자 회원 ID
     * @param saleAmount    판매액
     */
    SettlementResult settle(Long sellerOrderId, Long sellerId, BigDecimal saleAmount);
}

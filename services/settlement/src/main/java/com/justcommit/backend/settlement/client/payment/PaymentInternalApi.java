
package com.justcommit.backend.settlement.client.payment;

import com.justcommit.backend.common.client.InternalApiHeaders;
import com.justcommit.backend.common.response.ApiResponse;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@HttpExchange
public interface PaymentInternalApi {

    @PostExchange("/internal/v1/settlementPayouts")
    void distributeSettlement(
            @RequestHeader(InternalApiHeaders.IDEMPOTENCY_KEY)
            String idempotencyKey,
            @RequestBody SettlementPayoutRequest request
    );

    // payoutAmount = 판매액 - fee - sellerBurdenAmount (0원 · 음수 가능).
    // payment는 sellerOrderId로 배송비 수납 · 환불 중복을 막고, shippingFee · sellerBurdenAmount를 REVENUE로 옮긴다
    record SettlementPayoutRequest(
            Long settlementId,
            Long sellerId,
            Long sellerOrderId,
            BigDecimal payoutAmount,
            BigDecimal fee,
            BigDecimal shippingFee,
            BigDecimal sellerBurdenAmount
    ) {}

    // 정산 대사: settlementId마다 정산 원장(SETTLEMENT, SETTLEMENT_FEE, SETTLEMENT_COLLECTION, SELLER_BURDEN).
    // 한 번에 1~100개. 원장이 없는 settlementId도 빈 목록으로 온다
    @PostExchange("/internal/v1/settlementPayouts/ledgers")
    ApiResponse<SettlementLedgerResponse> findSettlementLedgers(@RequestBody SettlementLedgerRequest request);

    record SettlementLedgerRequest(List<Long> settlementIds) {}

    record SettlementLedgerResponse(Map<Long, List<SettlementLedgerEntry>> ledgers) {}
}

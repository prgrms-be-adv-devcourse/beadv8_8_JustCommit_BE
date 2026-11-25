package com.justcommit.backend.settlement.client.payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// payment 정산 원장 한 행 (POST /internal/v1/settlementPayouts/ledgers).
// walletType, refType, status는 payment enum 이름을 문자열로 받는다. payment가 값을 늘려도 역직렬화가 깨지지 않고,
// 모르는 값은 대사에서 불일치로 드러난다
// occurredAt: payment가 정산 지급을 처리한 시각 (wallet_transaction.ref_occurred_time)
public record SettlementLedgerEntry(
        Long walletId,
        String walletType,
        Long memberId,
        String refType,
        String status,
        BigDecimal amount,
        LocalDateTime occurredAt
) {
}

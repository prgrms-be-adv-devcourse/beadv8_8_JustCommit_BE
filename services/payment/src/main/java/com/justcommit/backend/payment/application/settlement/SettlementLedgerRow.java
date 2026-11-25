package com.justcommit.backend.payment.application.settlement;

import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.TransactionStatus;
import com.justcommit.backend.payment.domain.WalletType;
import java.math.BigDecimal;
import java.time.LocalDateTime;

// 정산 원장 조회 결과 한 행 (wallet_transaction + wallet). settlementId = ref_id
// memberId는 시스템 지갑(HOLDING · REVENUE)이면 null, occurredAt = ref_occurred_time
public record SettlementLedgerRow(
        Long settlementId,
        Long walletId,
        WalletType walletType,
        Long memberId,
        RefType refType,
        TransactionStatus status,
        BigDecimal amount,
        LocalDateTime occurredAt
) {
}

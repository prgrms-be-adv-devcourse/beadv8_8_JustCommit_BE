package com.justcommit.backend.payment.presentation.internal.dto;

import com.justcommit.backend.payment.application.settlement.SettlementLedgerRow;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.TransactionStatus;
import com.justcommit.backend.payment.domain.WalletType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 정산 원장 조회 응답. ledgers의 키는 요청한 settlementId(중복 제거)와 하나씩 맞고, 행이 없으면 빈 목록
public record SettlementLedgerResponse(Map<Long, List<Entry>> ledgers) {

    // status: 지갑 기준 IN · OUT, memberId: 시스템 지갑이면 null, occurredAt: ref_occurred_time
    public record Entry(
            Long walletId,
            WalletType walletType,
            Long memberId,
            RefType refType,
            TransactionStatus status,
            BigDecimal amount,
            LocalDateTime occurredAt
    ) {

        static Entry from(SettlementLedgerRow row) {
            return new Entry(row.walletId(), row.walletType(), row.memberId(), row.refType(),
                    row.status(), row.amount(), row.occurredAt());
        }
    }

    public static SettlementLedgerResponse from(Map<Long, List<SettlementLedgerRow>> ledgers) {
        Map<Long, List<Entry>> entries = new LinkedHashMap<>();
        ledgers.forEach((settlementId, rows) -> entries.put(settlementId, rows.stream().map(Entry::from).toList()));
        return new SettlementLedgerResponse(entries);
    }
}

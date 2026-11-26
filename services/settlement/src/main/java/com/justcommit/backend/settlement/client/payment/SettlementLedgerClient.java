package com.justcommit.backend.settlement.client.payment;

import java.util.Collection;
import java.util.List;
import java.util.Map;

// 정산 대사가 payment의 정산 원장을 읽는다
public interface SettlementLedgerClient {

    // settlementId마다 원장 행 목록 (1~100개). 실패하면 InternalApi*Exception
    Map<Long, List<SettlementLedgerEntry>> findLedgers(Collection<Long> settlementIds);
}

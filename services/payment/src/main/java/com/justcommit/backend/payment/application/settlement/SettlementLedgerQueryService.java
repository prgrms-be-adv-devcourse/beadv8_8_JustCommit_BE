package com.justcommit.backend.payment.application.settlement;

import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// settlement 대사가 payment DB를 직접 읽지 않고 정산별 원장 행을 받아 가는 읽기 전용 조회.
// 지갑을 잠그지 않는다 (지급 중인 정산은 커밋 전 행이 보이지 않을 뿐이고, 대사가 유예 시간을 두고 판단한다)
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementLedgerQueryService {

    // distributeSettlement()가 ref_id = settlementId로 남기는 거래 유형
    // (정산 안에서 옮기는 배송비 SHIPPING_FEE는 ref_id가 sellerOrderId,
    //  미수금 차감 RECEIVABLE_COLLECTION은 ref_id가 입금 거래 행 ID라 여기에 들어오지 않는다)
    public static final Set<RefType> SETTLEMENT_REF_TYPES = Set.of(
            RefType.SETTLEMENT,
            RefType.SETTLEMENT_FEE,
            RefType.SETTLEMENT_COLLECTION,
            RefType.SELLER_BURDEN);

    private final WalletTransactionRepository walletTransactionRepository;

    // @return 요청 순서(중복 제거)대로 settlementId → 원장 행. 행이 없는 정산도 빈 목록으로 들어간다
    @Transactional(readOnly = true)
    public Map<Long, List<SettlementLedgerRow>> findLedgers(Collection<Long> settlementIds) {
        Set<Long> distinctIds = new LinkedHashSet<>(settlementIds);
        Map<Long, List<SettlementLedgerRow>> ledgers = new LinkedHashMap<>();
        distinctIds.forEach(id -> ledgers.put(id, new ArrayList<>()));

        List<SettlementLedgerRow> rows =
                walletTransactionRepository.findSettlementLedgerRows(SETTLEMENT_REF_TYPES, distinctIds);
        rows.forEach(row -> ledgers.get(row.settlementId()).add(row));

        // 회원 ID · 금액은 남기지 않는다 (docs/infra/observability.md 「로그」)
        log.atInfo()
                .addKeyValue("requestedCount", distinctIds.size())
                .addKeyValue("rowCount", rows.size())
                .log("정산 원장을 조회했습니다.");
        return ledgers;
    }
}

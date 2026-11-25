package com.justcommit.backend.payment.infrastructure.repository;

import com.justcommit.backend.payment.application.settlement.SettlementLedgerRow;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.WalletTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {
    Page<WalletTransaction> findByWallet_Id(Long walletId, Pageable pageable);

    boolean existsByWallet_IdAndRefTypeAndRefId(Long walletId, RefType refType, Long refId);

    Optional<WalletTransaction> findByWallet_IdAndRefTypeAndRefId(Long walletId, RefType refType, Long refId);

    Page<WalletTransaction> findByWallet_IdAndRefType(Long walletId, RefType refType, Pageable pageable);

    // 정산 원장 조회 (settlement 대사용). 지갑까지 조인해 쿼리 한 번으로 가져온다.
    // wallet_id 조건을 일부러 넣지 않는다: 판매자 · HOLDING · REVENUE가 아닌 예상 밖 지갑에 기록된 행도
    // 대사가 찾아내야 하기 때문이다. 그래서 uk_wallet_transaction_ref(wallet_id, ...) 대신
    // idx_wallet_transaction_ref_id_ref_type(ref_id, ref_type, V6)로 조회한다
    @Query("""
            select new com.justcommit.backend.payment.application.settlement.SettlementLedgerRow(
                t.refId, w.id, w.walletType, w.memberId, t.refType, t.status, t.amount, t.refOccurredTime)
            from WalletTransaction t
            join t.wallet w
            where t.refType in :refTypes
              and t.refId in :settlementIds
            order by t.refId, t.id
            """)
    List<SettlementLedgerRow> findSettlementLedgerRows(@Param("refTypes") Collection<RefType> refTypes,
                                                       @Param("settlementIds") Collection<Long> settlementIds);
}

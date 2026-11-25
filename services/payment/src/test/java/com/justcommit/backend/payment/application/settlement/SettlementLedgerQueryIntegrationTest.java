package com.justcommit.backend.payment.application.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.payment.application.MemberWalletDepositor;
import com.justcommit.backend.payment.application.PaymentService;
import com.justcommit.backend.payment.application.WalletService;
import com.justcommit.backend.payment.config.JpaAuditingConfig;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.TransactionStatus;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.domain.WalletType;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

// 정산 원장 조회를 실제 정산 지급(distributeSettlement)이 남긴 행과 Flyway 스키마로 확인한다
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, PaymentService.class, WalletService.class, MemberWalletDepositor.class,
        SettlementLedgerQueryService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SettlementLedgerQueryIntegrationTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 11, 25, 10, 0);

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @Autowired SettlementLedgerQueryService settlementLedgerQueryService;
    @Autowired PaymentService paymentService;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository walletTransactionRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    // 시스템 지갑은 V1 마이그레이션이 만든 것을 쓴다
    @BeforeEach
    void resetSystemWallets() {
        jdbcTemplate.update("update wallet set balance = 100000 where wallet_type = 'HOLDING'");
        jdbcTemplate.update("update wallet set balance = 0 where wallet_type = 'REVENUE'");
    }

    @AfterEach
    void cleanUp() {
        walletTransactionRepository.deleteAllInBatch();
        jdbcTemplate.update("delete from wallet where wallet_type = 'MEMBER'");
    }

    @Test
    @DisplayName("양수 정산: HOLDING 출금, 판매자 입금, REVENUE 수수료 · 부담금 행이 지갑 정보와 함께 내려온다")
    void positiveSettlement() {
        Long sellerId = 10L;
        paymentService.distributeSettlement(1L, sellerId, null,
                amount("22500"), amount("1500"), BigDecimal.ZERO, amount("6000"));

        List<SettlementLedgerRow> rows = settlementLedgerQueryService.findLedgers(List.of(1L)).get(1L);

        Long sellerWalletId = walletIdOf(sellerId);
        assertThat(rows)
                .extracting(SettlementLedgerRow::walletType, SettlementLedgerRow::memberId, SettlementLedgerRow::refType,
                        SettlementLedgerRow::status, r -> r.amount().toPlainString())
                .containsExactlyInAnyOrder(
                        tuple(WalletType.HOLDING, null, RefType.SETTLEMENT, TransactionStatus.OUT, "30000"),
                        tuple(WalletType.REVENUE, null, RefType.SETTLEMENT_FEE, TransactionStatus.IN, "1500"),
                        tuple(WalletType.MEMBER, sellerId, RefType.SETTLEMENT, TransactionStatus.IN, "22500"),
                        tuple(WalletType.REVENUE, null, RefType.SELLER_BURDEN, TransactionStatus.IN, "6000"));
        assertThat(rows).filteredOn(r -> r.walletType() == WalletType.MEMBER)
                .extracting(SettlementLedgerRow::walletId).containsExactly(sellerWalletId);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.settlementId()).isEqualTo(1L);
            assertThat(r.occurredAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("0원 정산: 판매자 SETTLEMENT 0원 행과 REVENUE 행이 내려온다")
    void zeroSettlement() {
        Long sellerId = 11L;
        saveSeller(sellerId, "5000");
        paymentService.distributeSettlement(2L, sellerId, null,
                BigDecimal.ZERO, amount("1500"), BigDecimal.ZERO, amount("28500"));

        assertThat(settlementLedgerQueryService.findLedgers(List.of(2L)).get(2L))
                .extracting(SettlementLedgerRow::walletType, SettlementLedgerRow::refType,
                        SettlementLedgerRow::status, r -> r.amount().toPlainString())
                .containsExactlyInAnyOrder(
                        tuple(WalletType.HOLDING, RefType.SETTLEMENT, TransactionStatus.OUT, "30000"),
                        tuple(WalletType.REVENUE, RefType.SETTLEMENT_FEE, TransactionStatus.IN, "1500"),
                        tuple(WalletType.MEMBER, RefType.SETTLEMENT, TransactionStatus.IN, "0"),
                        tuple(WalletType.REVENUE, RefType.SELLER_BURDEN, TransactionStatus.IN, "28500"));
    }

    @Test
    @DisplayName("음수 정산: 회수(SETTLEMENT_COLLECTION)와 0원 SETTLEMENT 행이 내려오고, 미수금만큼 부담금이 줄어든다")
    void negativeSettlementWithReceivable() {
        Long sellerId = 12L;
        saveSeller(sellerId, "1000");
        paymentService.distributeSettlement(3L, sellerId, null,
                amount("-2500"), amount("500"), BigDecimal.ZERO, amount("13000"));

        assertThat(settlementLedgerQueryService.findLedgers(List.of(3L)).get(3L))
                .extracting(SettlementLedgerRow::walletType, SettlementLedgerRow::refType,
                        SettlementLedgerRow::status, r -> r.amount().toPlainString())
                .containsExactlyInAnyOrder(
                        tuple(WalletType.HOLDING, RefType.SETTLEMENT, TransactionStatus.OUT, "11000"),
                        tuple(WalletType.REVENUE, RefType.SETTLEMENT_FEE, TransactionStatus.IN, "500"),
                        tuple(WalletType.MEMBER, RefType.SETTLEMENT_COLLECTION, TransactionStatus.OUT, "1000"),
                        tuple(WalletType.MEMBER, RefType.SETTLEMENT, TransactionStatus.IN, "0"),
                        // 부담금 13000 - 미수금 1500
                        tuple(WalletType.REVENUE, RefType.SELLER_BURDEN, TransactionStatus.IN, "11500"));
    }

    @Test
    @DisplayName("여러 정산을 한 번에 조회하면 요청 순서대로, 중복은 하나로, 행 없는 정산은 빈 목록으로 내려온다")
    void multipleSettlementsWithMissingAndDuplicateIds() {
        paymentService.distributeSettlement(1L, 10L, null,
                amount("9500"), amount("500"), BigDecimal.ZERO, BigDecimal.ZERO);
        paymentService.distributeSettlement(2L, 11L, null,
                amount("4750"), amount("250"), BigDecimal.ZERO, BigDecimal.ZERO);

        Map<Long, List<SettlementLedgerRow>> ledgers =
                settlementLedgerQueryService.findLedgers(List.of(999L, 2L, 1L, 2L));

        assertThat(ledgers.keySet()).containsExactly(999L, 2L, 1L);
        assertThat(ledgers.get(999L)).isEmpty();
        assertThat(ledgers.get(1L)).hasSize(3).allMatch(r -> r.settlementId() == 1L);
        assertThat(ledgers.get(2L)).hasSize(3).allMatch(r -> r.settlementId() == 2L);
    }

    @Test
    @DisplayName("정산과 다른 판매자 지갑에 기록된 정산 행도 내려온다 (wallet_id로 거르지 않음)")
    void includesRowsInUnexpectedWallet() {
        Long sellerId = 10L;
        paymentService.distributeSettlement(1L, sellerId, null,
                amount("9500"), amount("500"), BigDecimal.ZERO, BigDecimal.ZERO);
        Wallet otherSeller = saveSeller(20L, "0");
        walletTransactionRepository.save(
                WalletTransaction.sellerSettlement(otherSeller, 1L, amount("9500"), OCCURRED_AT));

        assertThat(settlementLedgerQueryService.findLedgers(List.of(1L)).get(1L))
                .filteredOn(r -> r.refType() == RefType.SETTLEMENT && r.status() == TransactionStatus.IN)
                .extracting(SettlementLedgerRow::walletId, SettlementLedgerRow::memberId)
                .containsExactlyInAnyOrder(
                        tuple(walletIdOf(sellerId), sellerId),
                        tuple(otherSeller.getId(), 20L));
    }

    @Test
    @DisplayName("ref_id가 같아도 정산과 무관한 거래 유형(결제 · 배송비 · 미수금 차감 · 인출)은 내려오지 않는다")
    void excludesUnrelatedRefTypes() {
        Wallet member = saveSeller(30L, "50000");
        Wallet holding = walletRepository.findByWalletType(WalletType.HOLDING).orElseThrow();
        walletTransactionRepository.save(WalletTransaction.paymentOut(member, 7L, amount("1000"), OCCURRED_AT));
        walletTransactionRepository.save(WalletTransaction.holdingIn(holding, 7L, amount("1000"), OCCURRED_AT));
        walletTransactionRepository.save(WalletTransaction.shippingFeeOut(holding, 7L, amount("3000"), OCCURRED_AT));
        walletTransactionRepository.save(
                WalletTransaction.receivableCollectionOut(member, 7L, amount("500"), OCCURRED_AT));
        walletTransactionRepository.save(WalletTransaction.cashOut(member, 7L, amount("2000"), OCCURRED_AT));

        assertThat(settlementLedgerQueryService.findLedgers(List.of(7L))).containsExactly(Map.entry(7L, List.of()));
    }

    private Wallet saveSeller(Long memberId, String balance) {
        Wallet wallet = Wallet.forMember(memberId);
        if (new BigDecimal(balance).signum() > 0) {
            wallet.credit(new BigDecimal(balance));
        }
        return walletRepository.saveAndFlush(wallet);
    }

    private Long walletIdOf(Long memberId) {
        return walletRepository.findByMemberId(memberId).orElseThrow().getId();
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}

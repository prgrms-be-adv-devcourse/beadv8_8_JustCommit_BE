package com.justcommit.backend.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.payment.config.JpaAuditingConfig;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.TransactionStatus;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.domain.WalletType;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

// 정산 지급(부담금 · 배송비 · 0원 · 마이너스)을 Flyway 스키마(V3 미수금 칸 · CHECK · 유니크 제약)와 실제 행 잠금으로 확인한다
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, PaymentService.class, WalletService.class, MemberWalletDepositor.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentSettlementIntegrationTest {

    private static final int THREADS = 8;
    private static final Long SELLER_ORDER_ID = 500L;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

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
    @DisplayName("양수 정산: 판매액 + 배송비를 HOLDING에서 꺼내 판매자 · REVENUE(수수료 · 배송비 · 부담금)로 나눈다")
    void positiveWithBurdenAndShippingFee() {
        Long sellerId = 10L;

        paymentService.distributeSettlement(1L, sellerId, SELLER_ORDER_ID,
                amount("22500"), amount("1500"), amount("3000"), amount("6000"));

        assertThat(balanceOf(sellerId)).isEqualByComparingTo("22500");
        assertThat(systemBalance(WalletType.HOLDING)).isEqualByComparingTo("67000");
        assertThat(systemBalance(WalletType.REVENUE)).isEqualByComparingTo("10500");
        assertThat(walletTransactionRepository.findAll())
                .extracting(WalletTransaction::getRefType, WalletTransaction::getStatus, t -> t.getAmount().toPlainString())
                .containsExactlyInAnyOrder(
                        tuple(RefType.SETTLEMENT, TransactionStatus.OUT, "30000"),
                        tuple(RefType.SETTLEMENT_FEE, TransactionStatus.IN, "1500"),
                        tuple(RefType.SHIPPING_FEE, TransactionStatus.OUT, "3000"),
                        tuple(RefType.SHIPPING_FEE, TransactionStatus.IN, "3000"),
                        tuple(RefType.SETTLEMENT, TransactionStatus.IN, "22500"),
                        tuple(RefType.SELLER_BURDEN, TransactionStatus.IN, "6000"));
    }

    @Test
    @DisplayName("0원 정산: 판매자 잔액은 그대로이고 SETTLEMENT 0원 행 1건이 남는다")
    void zeroPayout() {
        Long sellerId = 11L;
        saveSeller(sellerId, "5000");

        paymentService.distributeSettlement(2L, sellerId, null,
                BigDecimal.ZERO, amount("1500"), BigDecimal.ZERO, amount("28500"));

        assertThat(balanceOf(sellerId)).isEqualByComparingTo("5000");
        assertThat(sellerRows(sellerId))
                .extracting(WalletTransaction::getRefType, t -> t.getAmount().toPlainString(),
                        t -> t.getBalanceAfter().toPlainString(), t -> t.getReceivableAfter().toPlainString())
                .containsExactly(tuple(RefType.SETTLEMENT, "0", "5000", "0"));
        assertThat(systemBalance(WalletType.REVENUE)).isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("마이너스 정산 일부 회수: 잔액을 모두 회수하고 부족분은 DB 미수금과 각 행의 receivable_after에 남는다")
    void negativePartiallyCollectedPersistsReceivable() {
        Long sellerId = 12L;
        saveSeller(sellerId, "1000");

        paymentService.distributeSettlement(3L, sellerId, null,
                amount("-2500"), amount("500"), BigDecimal.ZERO, amount("13000"));

        assertThat(jdbcTemplate.queryForObject(
                "select receivable from wallet where member_id = ?", BigDecimal.class, sellerId))
                .isEqualByComparingTo("1500");
        assertThat(balanceOf(sellerId)).isEqualByComparingTo("0");
        assertThat(sellerRows(sellerId))
                .extracting(WalletTransaction::getRefType, WalletTransaction::getStatus, t -> t.getAmount().toPlainString(),
                        t -> t.getBalanceAfter().toPlainString(), t -> t.getReceivableAfter().toPlainString())
                .containsExactlyInAnyOrder(
                        tuple(RefType.SETTLEMENT_COLLECTION, TransactionStatus.OUT, "1000", "0", "1500"),
                        tuple(RefType.SETTLEMENT, TransactionStatus.IN, "0", "0", "1500"));
        // HOLDING 11000 + 회수 1000 = REVENUE 수수료 500 + 부담금 11500
        assertThat(systemBalance(WalletType.HOLDING)).isEqualByComparingTo("89000");
        assertThat(systemBalance(WalletType.REVENUE)).isEqualByComparingTo("12000");
    }

    @Test
    @DisplayName("판매액 0(전부 반품) + 지갑 없는 판매자: 지갑을 만들고 전액 미수금, HOLDING은 그대로")
    void zeroSaleAmountForSellerWithoutWallet() {
        Long sellerId = 13L;

        paymentService.distributeSettlement(4L, sellerId, null,
                amount("-6000"), BigDecimal.ZERO, BigDecimal.ZERO, amount("6000"));

        Wallet seller = walletRepository.findByMemberId(sellerId).orElseThrow();
        assertThat(seller.getBalance()).isEqualByComparingTo("0");
        assertThat(seller.getReceivable()).isEqualByComparingTo("6000");
        assertThat(systemBalance(WalletType.HOLDING)).isEqualByComparingTo("100000");
        assertThat(systemBalance(WalletType.REVENUE)).isEqualByComparingTo("0");
        assertThat(walletTransactionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 SHIPPING_FEE_REFUND된 판매자 주문의 배송비는 옮기지 않고 정산은 지급한다")
    void skipsRefundedShippingFee() {
        Long buyerId = 20L;
        Long sellerId = 14L;
        saveSeller(buyerId, "0");
        paymentService.refundShippingFee(SELLER_ORDER_ID, buyerId, amount("3000"));

        paymentService.distributeSettlement(5L, sellerId, SELLER_ORDER_ID,
                amount("9500"), amount("500"), amount("3000"), BigDecimal.ZERO);

        assertThat(balanceOf(sellerId)).isEqualByComparingTo("9500");
        // 배송비 환불 3000 + 판매액 10000
        assertThat(systemBalance(WalletType.HOLDING)).isEqualByComparingTo("87000");
        assertThat(walletTransactionRepository.findAll()).noneMatch(t -> t.getRefType() == RefType.SHIPPING_FEE);
    }

    @Test
    @DisplayName("배송비를 이미 수납했으면 정산 지급 때 다시 옮기지 않는다")
    void doesNotMoveShippingFeeTwice() {
        Long sellerId = 15L;
        paymentService.collectShippingFee(SELLER_ORDER_ID, amount("3000"));

        paymentService.distributeSettlement(6L, sellerId, SELLER_ORDER_ID,
                amount("9500"), amount("500"), amount("3000"), BigDecimal.ZERO);

        assertThat(systemBalance(WalletType.HOLDING)).isEqualByComparingTo("87000");
        assertThat(systemBalance(WalletType.REVENUE)).isEqualByComparingTo("3500");
        assertThat(walletTransactionRepository.findAll())
                .filteredOn(t -> t.getRefType() == RefType.SHIPPING_FEE).hasSize(2);
    }

    @Test
    @DisplayName("같은 마이너스 정산이 동시에 와도 회수 · 미수금 · 배송비 이동은 한 번만 일어난다")
    void concurrentNegativeSettlementIsProcessedOnce() throws Exception {
        Long sellerId = 16L;
        saveSeller(sellerId, "1000");

        runConcurrently(() -> {
            paymentService.distributeSettlement(7L, sellerId, SELLER_ORDER_ID,
                    amount("-2500"), amount("500"), amount("3000"), amount("13000"));
            return null;
        });

        Wallet seller = walletRepository.findByMemberId(sellerId).orElseThrow();
        assertThat(seller.getBalance()).isEqualByComparingTo("0");
        assertThat(seller.getReceivable()).isEqualByComparingTo("1500");
        assertThat(systemBalance(WalletType.HOLDING)).isEqualByComparingTo("86000");
        assertThat(systemBalance(WalletType.REVENUE)).isEqualByComparingTo("15000");
        // HOLDING 판매액 · 배송비 출금 + REVENUE 수수료 · 배송비 · 부담금 + 판매자 회수 · 정산
        assertThat(walletTransactionRepository.count()).isEqualTo(7);
    }

    @Test
    @DisplayName("같은 0원 정산을 다시 보내도 SETTLEMENT 행과 REVENUE 입금은 한 번만")
    void zeroPayoutIsProcessedOnce() {
        Long sellerId = 17L;

        for (int i = 0; i < 2; i++) {
            paymentService.distributeSettlement(8L, sellerId, null,
                    BigDecimal.ZERO, amount("1500"), BigDecimal.ZERO, amount("28500"));
        }

        assertThat(systemBalance(WalletType.HOLDING)).isEqualByComparingTo("70000");
        assertThat(systemBalance(WalletType.REVENUE)).isEqualByComparingTo("30000");
        assertThat(walletTransactionRepository.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("DB가 음수 미수금을 막는다 (ck_wallet_receivable_non_negative)")
    void rejectsNegativeReceivableInDatabase() {
        saveSeller(18L, "0");

        assertThatThrownBy(() -> jdbcTemplate.update("update wallet set receivable = -1 where member_id = 18"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_wallet_receivable_non_negative");
    }

    private void saveSeller(Long memberId, String balance) {
        Wallet wallet = Wallet.forMember(memberId);
        if (new BigDecimal(balance).signum() > 0) {
            wallet.credit(new BigDecimal(balance));
        }
        walletRepository.saveAndFlush(wallet);
    }

    private List<WalletTransaction> sellerRows(Long sellerId) {
        Long walletId = walletRepository.findByMemberId(sellerId).orElseThrow().getId();
        return walletTransactionRepository.findByWallet_Id(walletId, Pageable.unpaged()).getContent();
    }

    private BigDecimal balanceOf(Long memberId) {
        return walletRepository.findByMemberId(memberId).orElseThrow().getBalance();
    }

    private BigDecimal systemBalance(WalletType type) {
        return walletRepository.findByWalletType(type).orElseThrow().getBalance();
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }

    private void runConcurrently(Callable<Void> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < THREADS; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS); // 예외가 나면 테스트 실패
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}

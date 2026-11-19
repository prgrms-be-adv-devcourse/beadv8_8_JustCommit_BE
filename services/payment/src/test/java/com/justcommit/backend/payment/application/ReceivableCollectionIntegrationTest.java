package com.justcommit.backend.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.payment.config.ChargeRecoveryConfig;
import com.justcommit.backend.payment.config.ClockConfig;
import com.justcommit.backend.payment.config.JpaAuditingConfig;
import com.justcommit.backend.payment.domain.Charge;
import com.justcommit.backend.payment.domain.ChargeType;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.TransactionStatus;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.domain.WalletType;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import com.justcommit.backend.payment.infrastructure.crypto.PaymentKeyCipher;
import com.justcommit.backend.payment.infrastructure.repository.ChargeRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;
import com.justcommit.backend.payment.presentation.dto.ChargeConfirmRequest;
import com.justcommit.backend.payment.presentation.dto.ChargeConfirmResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

// 입금 시 미수금 차감(#380)을 충전 승인, 양수 정산, 환불 경로에서 Flyway 스키마와 실제 행 잠금으로 확인한다
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ClockConfig.class, ChargeRecoveryConfig.class, PaymentKeyCipher.class,
        ChargeService.class, PaymentService.class, WalletService.class, MemberWalletDepositor.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReceivableCollectionIntegrationTest {

    private static final Long MEMBER_ID = 30L;
    private static final BigDecimal HOLDING_START = new BigDecimal("100000");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @Autowired ChargeService chargeService;
    @Autowired PaymentService paymentService;
    @Autowired ChargeRepository chargeRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository walletTransactionRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    // 시스템 지갑은 V1 마이그레이션이 만든 것을 쓴다
    @BeforeEach
    void resetSystemWallets() {
        jdbcTemplate.update("update wallet set balance = ? where wallet_type = 'HOLDING'", HOLDING_START);
        jdbcTemplate.update("update wallet set balance = 0 where wallet_type = 'REVENUE'");
    }

    @AfterEach
    void cleanUp() {
        walletTransactionRepository.deleteAllInBatch();
        chargeRepository.deleteAllInBatch();
        jdbcTemplate.update("delete from wallet where wallet_type = 'MEMBER'");
    }

    // ===== 충전 =====

    @Test
    @DisplayName("충전 승인, 미수금 < 충전액: 미수금 3000을 먼저 빼 REVENUE로, 응답 잔액은 차감 뒤 7000")
    void chargeCollectsReceivable() {
        Wallet member = saveMember("0", "3000");
        Charge charge = pendingCharge(member, "10000");

        ChargeConfirmResponse response = chargeService.completeConfirm(charge.getId(), LocalDateTime.now());

        assertThat(response.getBalanceAfter()).isEqualByComparingTo("7000");
        assertMember("7000", "0");
        assertThat(systemBalance(WalletType.REVENUE)).isEqualByComparingTo("3000");
        Long depositId = depositRowId(RefType.CHARGE);
        assertThat(rowsOf(memberWalletId()))
                .containsExactly(
                        row(RefType.CHARGE, charge.getId(), TransactionStatus.IN, "10000", "10000", "3000"),
                        row(RefType.RECEIVABLE_COLLECTION, depositId, TransactionStatus.OUT, "3000", "7000", "0"));
        assertThat(rowsOf(systemWalletId(WalletType.REVENUE)))
                .containsExactly(row(RefType.RECEIVABLE_COLLECTION, depositId, TransactionStatus.IN, "3000", "3000", "0"));
        assertChained(memberWalletId(), "0", "3000");
        assertChained(systemWalletId(WalletType.REVENUE), "0", "0");
    }

    @Test
    @DisplayName("충전 승인을 다시 반영하려 하면 CHARGE_ALREADY_PROCESSED로 끝나고 미수금 차감은 한 번만")
    void chargeRetryCollectsOnce() {
        Wallet member = saveMember("0", "3000");
        Charge charge = pendingCharge(member, "10000");
        chargeService.completeConfirm(charge.getId(), LocalDateTime.now());

        assertThatThrownBy(() -> chargeService.completeConfirm(charge.getId(), LocalDateTime.now()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.CHARGE_ALREADY_PROCESSED);

        assertMember("7000", "0");
        assertThat(systemBalance(WalletType.REVENUE)).isEqualByComparingTo("3000");
        assertThat(countOf(RefType.RECEIVABLE_COLLECTION)).isEqualTo(2);
    }

    @Test
    @DisplayName("미수금이 없으면 충전 승인은 입금 행만 남기고 REVENUE는 그대로")
    void chargeWithoutReceivable() {
        Wallet member = saveMember("0", "0");
        Charge charge = pendingCharge(member, "10000");

        ChargeConfirmResponse response = chargeService.completeConfirm(charge.getId(), LocalDateTime.now());

        assertThat(response.getBalanceAfter()).isEqualByComparingTo("10000");
        assertMember("10000", "0");
        assertThat(systemBalance(WalletType.REVENUE)).isEqualByComparingTo("0");
        assertThat(countOf(RefType.RECEIVABLE_COLLECTION)).isZero();
    }

    // ===== 정산 =====

    @Test
    @DisplayName("[S-G05] 양수 정산, 미수금 ≥ 정산금: 정산금 전부를 차감해 잔액 0, 남은 미수금 2500. 재요청해도 차감 한 번만")
    void settlementCollectsReceivable() {
        saveMember("0", "12000");

        // 판매액 10000 = 정산금 9500 + 수수료 500
        paymentService.distributeSettlement(1L, MEMBER_ID, null,
                new BigDecimal("9500"), new BigDecimal("500"), BigDecimal.ZERO, BigDecimal.ZERO);
        paymentService.distributeSettlement(1L, MEMBER_ID, null,
                new BigDecimal("9500"), new BigDecimal("500"), BigDecimal.ZERO, BigDecimal.ZERO);

        assertMember("0", "2500");
        assertThat(systemBalance(WalletType.HOLDING)).isEqualByComparingTo("90000");
        assertThat(systemBalance(WalletType.REVENUE)).isEqualByComparingTo("10000");
        Long depositId = depositRowId(RefType.SETTLEMENT);
        assertThat(rowsOf(memberWalletId()))
                .containsExactly(
                        row(RefType.SETTLEMENT, 1L, TransactionStatus.IN, "9500", "9500", "12000"),
                        row(RefType.RECEIVABLE_COLLECTION, depositId, TransactionStatus.OUT, "9500", "0", "2500"));
        assertChained(memberWalletId(), "0", "12000");
        assertChained(systemWalletId(WalletType.REVENUE), "0", "0");
        assertChained(systemWalletId(WalletType.HOLDING), HOLDING_START.toPlainString(), "0");
        assertConserved("0");
    }

    // ===== 환불 =====

    @Test
    @DisplayName("환불, 미수금 < 환불액: 차감 뒤 잔액을 돌려주고, 재요청은 ALREADY_REFUNDED와 같은 잔액으로 차감 없이")
    void refundCollectsReceivable() {
        saveMember("0", "1000");

        PaymentResult first = paymentService.refundCancelledItem(77L, MEMBER_ID, new BigDecimal("3000"));
        PaymentResult retry = paymentService.refundCancelledItem(77L, MEMBER_ID, new BigDecimal("3000"));

        assertThat(first.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(first.balanceAfter()).isEqualByComparingTo("2000");
        assertThat(retry.status()).isEqualTo(PaymentStatus.ALREADY_REFUNDED);
        assertThat(retry.balanceAfter()).isEqualByComparingTo("2000");
        assertMember("2000", "0");
        assertThat(systemBalance(WalletType.HOLDING)).isEqualByComparingTo("97000");
        assertThat(systemBalance(WalletType.REVENUE)).isEqualByComparingTo("1000");
        Long depositId = depositRowId(RefType.ORDER_ITEM_CANCEL_REFUND);
        assertThat(rowsOf(memberWalletId()))
                .containsExactly(
                        row(RefType.ORDER_ITEM_CANCEL_REFUND, 77L, TransactionStatus.IN, "3000", "3000", "1000"),
                        row(RefType.RECEIVABLE_COLLECTION, depositId, TransactionStatus.OUT, "1000", "2000", "0"));
        assertChained(memberWalletId(), "0", "1000");
        assertChained(systemWalletId(WalletType.HOLDING), HOLDING_START.toPlainString(), "0");
        assertConserved("0");
    }

    @Test
    @DisplayName("같은 회원에게 환불 여러 건이 동시에 들어와도 미수금보다 많이 차감하지 않고 합계가 맞는다")
    void concurrentRefundsCollectExactlyReceivable() throws Exception {
        saveMember("0", "5000");
        int threads = 4;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<PaymentResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                long orderItemId = 100L + i;
                Callable<PaymentResult> task = () -> {
                    ready.countDown();
                    start.await();
                    return paymentService.refundCancelledItem(orderItemId, MEMBER_ID, new BigDecimal("3000"));
                };
                futures.add(executor.submit(task));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            for (Future<PaymentResult> future : futures) {
                assertThat(future.get(20, TimeUnit.SECONDS).status()).isEqualTo(PaymentStatus.REFUNDED);
            }
        } finally {
            executor.shutdownNow();
        }

        // 환불 12000 - 미수금 5000
        assertMember("7000", "0");
        assertThat(systemBalance(WalletType.REVENUE)).isEqualByComparingTo("5000");
        assertChained(memberWalletId(), "0", "5000");
        assertChained(systemWalletId(WalletType.REVENUE), "0", "0");
        assertConserved("0");
    }

    // ===== 도우미 =====

    private Wallet saveMember(String balance, String receivable) {
        Wallet wallet = Wallet.forMember(MEMBER_ID);
        if (new BigDecimal(balance).signum() > 0) {
            wallet.credit(new BigDecimal(balance));
        }
        if (new BigDecimal(receivable).signum() > 0) {
            wallet.increaseReceivable(new BigDecimal(receivable));
        }
        return walletRepository.save(wallet);
    }

    private Charge pendingCharge(Wallet member, String amount) {
        Charge charge = chargeRepository.save(Charge.ready(member, ChargeType.TOSS, new BigDecimal(amount)));
        ChargeConfirmRequest request = new ChargeConfirmRequest();
        ReflectionTestUtils.setField(request, "paymentKey", "pk-" + charge.getId());
        ReflectionTestUtils.setField(request, "orderId", charge.getPgOrderNo());
        ReflectionTestUtils.setField(request, "amount", charge.getAmount());
        chargeService.startConfirm(MEMBER_ID, charge.getId(), request);
        return charge;
    }

    private void assertMember(String balance, String receivable) {
        Wallet member = walletRepository.findByMemberId(MEMBER_ID).orElseThrow();
        assertThat(member.getBalance()).isEqualByComparingTo(balance);
        assertThat(member.getReceivable()).isEqualByComparingTo(receivable);
        // 불변식: 미수금이 남아 있으면 잔액은 0
        if (member.getReceivable().signum() > 0) {
            assertThat(member.getBalance()).isEqualByComparingTo("0");
        }
    }

    // 회원 지갑 + HOLDING + REVENUE 잔액 합계는 처리 전과 같다 (충전처럼 밖에서 들어온 돈이 없을 때)
    private void assertConserved(String memberStartBalance) {
        BigDecimal total = walletRepository.findByMemberId(MEMBER_ID).orElseThrow().getBalance()
                .add(systemBalance(WalletType.HOLDING))
                .add(systemBalance(WalletType.REVENUE));
        assertThat(total).isEqualByComparingTo(HOLDING_START.add(new BigDecimal(memberStartBalance)));
    }

    // 원장을 ID 순서로 따라가면 balance_after, receivable_after가 앞 행에서 이어진다.
    // 이 테스트에서 미수금을 바꾸는 행은 RECEIVABLE_COLLECTION OUT뿐이다
    private void assertChained(Long walletId, String startBalance, String startReceivable) {
        BigDecimal balance = new BigDecimal(startBalance);
        BigDecimal receivable = new BigDecimal(startReceivable);
        for (WalletTransaction t : ledgerOf(walletId)) {
            balance = t.getStatus() == TransactionStatus.IN ? balance.add(t.getAmount()) : balance.subtract(t.getAmount());
            if (t.getRefType() == RefType.RECEIVABLE_COLLECTION && t.getStatus() == TransactionStatus.OUT) {
                receivable = receivable.subtract(t.getAmount());
            }
            assertThat(t.getBalanceAfter()).as("balance_after of row %d", t.getId()).isEqualByComparingTo(balance);
            assertThat(t.getReceivableAfter()).as("receivable_after of row %d", t.getId()).isEqualByComparingTo(receivable);
        }
        Wallet wallet = walletRepository.findById(walletId).orElseThrow();
        assertThat(wallet.getBalance()).isEqualByComparingTo(balance);
        assertThat(wallet.getReceivable()).isEqualByComparingTo(receivable);
    }

    private List<WalletTransaction> ledgerOf(Long walletId) {
        return walletTransactionRepository.findAll().stream()
                .filter(t -> t.getWallet().getId().equals(walletId))
                .sorted(Comparator.comparing(WalletTransaction::getId))
                .toList();
    }

    // (거래 유형, 대상 ID, 입출금, 금액, 처리 후 잔액, 처리 후 미수금)
    private List<List<Object>> rowsOf(Long walletId) {
        return ledgerOf(walletId).stream()
                .map(t -> List.<Object>of(t.getRefType(), t.getRefId(), t.getStatus(),
                        t.getAmount().stripTrailingZeros().toPlainString(),
                        t.getBalanceAfter().stripTrailingZeros().toPlainString(),
                        t.getReceivableAfter().stripTrailingZeros().toPlainString()))
                .toList();
    }

    private static List<Object> row(RefType refType, Long refId, TransactionStatus status,
                                    String amount, String balanceAfter, String receivableAfter) {
        return List.of(refType, refId, status, amount, balanceAfter, receivableAfter);
    }

    private Long depositRowId(RefType refType) {
        return ledgerOf(memberWalletId()).stream()
                .filter(t -> t.getRefType() == refType)
                .findFirst().orElseThrow().getId();
    }

    private long countOf(RefType refType) {
        return walletTransactionRepository.findAll().stream().filter(t -> t.getRefType() == refType).count();
    }

    private Long memberWalletId() {
        return walletRepository.findByMemberId(MEMBER_ID).orElseThrow().getId();
    }

    private Long systemWalletId(WalletType type) {
        return walletRepository.findByWalletType(type).orElseThrow().getId();
    }

    private BigDecimal systemBalance(WalletType type) {
        return walletRepository.findByWalletType(type).orElseThrow().getBalance();
    }
}

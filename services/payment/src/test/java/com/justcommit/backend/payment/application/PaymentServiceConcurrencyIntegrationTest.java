package com.justcommit.backend.payment.application;

import com.justcommit.backend.payment.config.JpaAuditingConfig;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.TransactionStatus;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletType;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.check_nullability=false"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, PaymentService.class, WalletService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentServiceConcurrencyIntegrationTest {

    private static final int THREADS = 8;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired PaymentService paymentUseCase;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository walletTransactionRepository;

    @BeforeEach
    void setUpHolding() {
        walletRepository.save(Wallet.system(WalletType.HOLDING));
    }

    @AfterEach
    void cleanUp() {
        walletTransactionRepository.deleteAllInBatch();
        walletRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("같은 주문을 동시에 결제해도 한 번만 차감되고, 차감액만큼 HOLDING에 적립된다")
    void debitsOnlyOnceForConcurrentRequestsOfSameOrder() throws Exception {
        Long buyerId = 1L;
        saveWallet(buyerId, "10000");
        Long orderId = 500L;

        List<PaymentResult> results = runConcurrently(
                i -> () -> paymentUseCase.pay(orderId, buyerId, new BigDecimal("3000")));

        assertThat(results).filteredOn(r -> r.status() == PaymentStatus.PAID).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() == PaymentStatus.ALREADY_PAID).hasSize(THREADS - 1);

        assertThat(balanceOf(buyerId)).isEqualByComparingTo("7000");
        assertThat(holdingBalance()).isEqualByComparingTo("3000");

        // 구매자 출금 1건 + HOLDING 입금 1건
        assertThat(walletTransactionRepository.count()).isEqualTo(2);
        assertThat(findTransaction(walletIdOf(buyerId), orderId).getStatus()).isEqualTo(TransactionStatus.OUT);
        assertThat(findTransaction(holdingWalletId(), orderId).getStatus()).isEqualTo(TransactionStatus.IN);
    }

    @Test
    @DisplayName("다른 주문을 동시에 결제해도 잔액을 넘지 않고, 결제된 금액 합계만큼 HOLDING에 적립된다")
    void neverOverdrawsWhenDifferentOrdersPayConcurrently() throws Exception {
        Long buyerId = 2L;
        saveWallet(buyerId, "10000");

        // 3000원짜리 주문 8건 → 3건만 결제 가능
        List<PaymentResult> results = runConcurrently(
                i -> () -> paymentUseCase.pay(1000L + i, buyerId, new BigDecimal("3000")));

        assertThat(results).filteredOn(r -> r.status() == PaymentStatus.PAID).hasSize(3);
        assertThat(results).filteredOn(r -> r.status() == PaymentStatus.INSUFFICIENT_BALANCE)
                .hasSize(THREADS - 3);

        assertThat(balanceOf(buyerId)).isEqualByComparingTo("1000");
        assertThat(holdingBalance()).isEqualByComparingTo("9000");

        // 결제 3건 × (구매자 출금 + HOLDING 입금)
        assertThat(walletTransactionRepository.count()).isEqualTo(6);
    }

    @Test
    @DisplayName("HOLDING 지갑이 없으면 예외가 나고 구매자 차감과 거래내역이 모두 롤백된다")
    void rollsBackBuyerDebitWhenHoldingWalletMissing() {
        walletRepository.deleteAllInBatch(); // @BeforeEach에서 만든 HOLDING 지갑을 지운다
        Long buyerId = 3L;
        saveWallet(buyerId, "10000");

        assertThatThrownBy(() -> paymentUseCase.pay(700L, buyerId, new BigDecimal("3000")))
                .isInstanceOf(BusinessException.class);

        assertThat(balanceOf(buyerId)).isEqualByComparingTo("10000");
        assertThat(walletTransactionRepository.count()).isZero();
    }

    @Test
    void allowsSameRefOnDifferentWallets() {
        Wallet buyerWallet = walletRepository.save(Wallet.forMember(11L));
        Wallet escrowWallet = walletRepository.save(Wallet.forMember(12L));

        walletTransactionRepository.saveAndFlush(
                WalletTransaction.paymentOut(buyerWallet, 900L, new BigDecimal("3000"), LocalDateTime.now()));
        walletTransactionRepository.saveAndFlush(
                WalletTransaction.paymentOut(escrowWallet, 900L, new BigDecimal("3000"), LocalDateTime.now()));

        assertThat(walletTransactionRepository.count()).isEqualTo(2);
    }

    @Test
    void rejectsDuplicateRefOnSameWallet() {
        Wallet buyerWallet = walletRepository.save(Wallet.forMember(13L));
        walletTransactionRepository.saveAndFlush(
                WalletTransaction.paymentOut(buyerWallet, 901L, new BigDecimal("3000"), LocalDateTime.now()));

        assertThatThrownBy(() -> walletTransactionRepository.saveAndFlush(
                WalletTransaction.paymentOut(buyerWallet, 901L, new BigDecimal("3000"), LocalDateTime.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("지갑이 없는 판매자에게 정산하면 지갑을 만들고 정산금을 적립한다")
    void createsSellerWalletOnFirstSettlement() {
        Long sellerId = 77L;
        Wallet holding = walletRepository.findByWalletType(WalletType.HOLDING).orElseThrow();
        holding.credit(new BigDecimal("10000"));
        walletRepository.saveAndFlush(holding);
        walletRepository.saveAndFlush(Wallet.system(WalletType.REVENUE));

        paymentUseCase.distributeSettlement(1L, sellerId, new BigDecimal("9500"), new BigDecimal("500"));

        assertThat(balanceOf(sellerId)).isEqualByComparingTo("9500");
        assertThat(holdingBalance()).isEqualByComparingTo("0");
        assertThat(revenueBalance()).isEqualByComparingTo("500");
    }

    private void saveWallet(Long memberId, String balance) {
        Wallet wallet = Wallet.forMember(memberId);
        wallet.credit(new BigDecimal(balance));
        walletRepository.save(wallet);
    }

    private BigDecimal balanceOf(Long memberId) {
        return walletRepository.findByMemberId(memberId).orElseThrow().getBalance();
    }

    private BigDecimal holdingBalance() {
        return walletRepository.findByWalletType(WalletType.HOLDING).orElseThrow().getBalance();
    }

    private BigDecimal revenueBalance() {
        return walletRepository.findByWalletType(WalletType.REVENUE).orElseThrow().getBalance();
    }

    private Long holdingWalletId() {
        return walletRepository.findByWalletType(WalletType.HOLDING).orElseThrow().getId();
    }

    private Long walletIdOf(Long memberId) {
        return walletRepository.findByMemberId(memberId).orElseThrow().getId();
    }

    private WalletTransaction findTransaction(Long walletId, Long orderId) {
        return walletTransactionRepository.findAll().stream()
                .filter(t -> t.getWallet().getId().equals(walletId))
                .filter(t -> t.getRefType() == RefType.PAYMENT && t.getRefId().equals(orderId))
                .findFirst()
                .orElseThrow();
    }

    private List<PaymentResult> runConcurrently(
            IntFunction<Callable<PaymentResult>> taskFactory) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PaymentResult>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < THREADS; i++) {
                Callable<PaymentResult> task = taskFactory.apply(i);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            List<PaymentResult> results = new ArrayList<>();
            for (Future<PaymentResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS)); // 예외가 나면 테스트 실패
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("여러 구매자가 동시에 결제해도 HOLDING에 정확히 적립된다")
    void concurrentPaymentsFromDifferentBuyers() throws Exception {

        // 구매자 8명에게 각각 10,000원 지급
        for (int i = 0; i < THREADS; i++) {
            saveWallet(100L + i, "10000");
        }

        // 8명이 동시에 3,000원씩 결제
        List<PaymentResult> results = runConcurrently(
                i -> () -> paymentUseCase.pay(
                        1000L + i,
                        100L + i,
                        new BigDecimal("3000")
                )
        );

        // 8건 모두 결제 성공
        assertThat(results)
                .filteredOn(r -> r.status() == PaymentStatus.PAID)
                .hasSize(THREADS);

        // HOLDING에는 3,000원 × 8명 = 24,000원
        assertThat(holdingBalance())
                .isEqualByComparingTo("24000");

        // 거래내역: 구매자 출금 8건 + HOLDING 입금 8건
        assertThat(walletTransactionRepository.count()).isEqualTo(16);
    }

    @Test
    @DisplayName("HOLDING 거래내역 저장 실패 시 전체 롤백된다")
    void rollsBackWhenHoldingTransactionSaveFails() {

        Long buyerId = 3L;
        Long orderId = 700L;
        saveWallet(buyerId, "10000");

        Wallet holdingWallet = walletRepository
                .findByWalletType(WalletType.HOLDING).orElseThrow();

        // 같은 주문의 HOLDING 거래내역을 미리 저장
        walletTransactionRepository.saveAndFlush(
                WalletTransaction.holdingIn(
                        holdingWallet, orderId,
                        new BigDecimal("3000"), LocalDateTime.now()
                )
        );

        // 결제하면 HOLDING 거래내역 중복으로 DB 오류 발생
        assertThatThrownBy(() ->
                paymentUseCase.pay(orderId, buyerId, new BigDecimal("3000"))
        ).isInstanceOf(DataIntegrityViolationException.class);

        // 구매자 잔액이 원래대로 복구됐는지
        assertThat(balanceOf(buyerId))
                .isEqualByComparingTo("10000");

        // HOLDING 입금도 취소됐는지
        assertThat(holdingBalance())
                .isEqualByComparingTo("0");

        // 미리 저장한 거래내역 1건만 남아야 함
        assertThat(walletTransactionRepository.count()).isEqualTo(1);
    }


    @Test
    @DisplayName("배송비 수납과 환불이 동시에 발생해도 한쪽만 처리된다")
    void preventsConcurrentShippingFeeCollectionAndRefund() throws Exception {
        Long sellerOrderId = 700L;
        Long buyerId = 20L;
        BigDecimal shippingFee = new BigDecimal("3000");


        // 초기 지갑 준비
        Wallet holding = walletRepository.findByWalletType(WalletType.HOLDING)
                .orElseThrow();
        holding.credit(new BigDecimal("10000"));
        walletRepository.saveAndFlush(holding);

        walletRepository.saveAndFlush(Wallet.forMember(buyerId));
        walletRepository.saveAndFlush(Wallet.system(WalletType.REVENUE));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            // 배송비 수납
            Future<?> collect = executor.submit(() -> {
                ready.countDown();
                start.await();

                paymentUseCase.collectShippingFee(
                        sellerOrderId, shippingFee);
                return null;
            });

            // 배송비 환불
            Future<?> refund = executor.submit(() -> {
                ready.countDown();
                start.await();

                try {
                    paymentUseCase.refundShippingFee(
                            sellerOrderId, buyerId, shippingFee);
                } catch (BusinessException e) {
                    if (e.getErrorCode()
                            != PaymentErrorCode.SHIPPING_FEE_ALREADY_COLLECTED) {
                        throw e;
                    }
                }
                return null;
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            collect.get(30, TimeUnit.SECONDS);
            refund.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        // 두 거래 유형 중 하나만 처리되어야 한다
        List<WalletTransaction> transactions =
                walletTransactionRepository.findAll();

        long collected = transactions.stream()
                .filter(t -> t.getRefType() == RefType.SHIPPING_FEE)
                .count();

        long refunded = transactions.stream()
                .filter(t -> t.getRefType() == RefType.SHIPPING_FEE_REFUND)
                .count();

        // 성공한 한 작업의 OUT, IN 내역 2건만 존재
        assertThat(collected).isIn(0L, 2L);
        assertThat(refunded).isEqualTo(2L - collected);

        // 잔액 검증
        assertThat(holdingBalance())
                .isEqualByComparingTo("7000");

        BigDecimal buyerBalance = balanceOf(buyerId);
        BigDecimal revenueBalance = revenueBalance();

        assertThat(buyerBalance.add(revenueBalance))
                .isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("배송비가 먼저 수납되면 환불은 차단되고, 잔액과 거래내역이 그대로 유지된다")
    void rejectsRefundAfterShippingFeeCollected() {
        Long sellerOrderId = 701L;
        Long buyerId = 21L;
        BigDecimal shippingFee = new BigDecimal("3000");

        Wallet holding = walletRepository.findByWalletType(WalletType.HOLDING).orElseThrow();
        holding.credit(new BigDecimal("10000")); // 이 배송비 + 다른 주문 돈
        walletRepository.saveAndFlush(holding);
        walletRepository.saveAndFlush(Wallet.forMember(buyerId));
        walletRepository.saveAndFlush(Wallet.system(WalletType.REVENUE));

        paymentUseCase.collectShippingFee(sellerOrderId, shippingFee);
        long txCount = walletTransactionRepository.count();

        assertThatThrownBy(() -> paymentUseCase.refundShippingFee(sellerOrderId, buyerId, shippingFee))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.SHIPPING_FEE_ALREADY_COLLECTED);

        assertThat(holdingBalance()).isEqualByComparingTo("7000");
        assertThat(revenueBalance()).isEqualByComparingTo("3000");
        assertThat(balanceOf(buyerId)).isEqualByComparingTo("0");
        assertThat(walletTransactionRepository.count()).isEqualTo(txCount);
    }

}
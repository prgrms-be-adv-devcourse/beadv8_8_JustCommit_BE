package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.config.JpaAuditingConfig;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.MemberQuery;
import com.justcommit.backend.payment.PaymentResult;
import com.justcommit.backend.payment.PaymentStatus;
import com.justcommit.backend.payment.PaymentUseCase;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.TransactionStatus;
import com.justcommit.backend.payment.domain.Wallet;
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
@Import({JpaAuditingConfig.class, PaymentService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentServiceConcurrencyIntegrationTest {

    private static final int THREADS = 8;
    private static final Long HOLDING_ID = 999L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired PaymentUseCase paymentUseCase;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository walletTransactionRepository;

    // PaymentService가 HOLDING 회원 ID를 member 모듈에서 조회하므로 목으로 대체
    @MockitoBean MemberQuery memberQuery;

    @BeforeEach
    void setUpHolding() {
        given(memberQuery.getHoldingMemberId()).willReturn(HOLDING_ID);
        walletRepository.save(new Wallet(HOLDING_ID));
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
        assertThat(balanceOf(HOLDING_ID)).isEqualByComparingTo("3000");

        // 구매자 출금 1건 + HOLDING 입금 1건
        assertThat(walletTransactionRepository.count()).isEqualTo(2);
        assertThat(findTransaction(buyerId, orderId).getStatus()).isEqualTo(TransactionStatus.OUT);
        assertThat(findTransaction(HOLDING_ID, orderId).getStatus()).isEqualTo(TransactionStatus.IN);
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
        assertThat(balanceOf(HOLDING_ID)).isEqualByComparingTo("9000");

        // 결제 3건 × (구매자 출금 + HOLDING 입금)
        assertThat(walletTransactionRepository.count()).isEqualTo(6);
    }

    @Test
    @DisplayName("HOLDING 지갑이 없으면 예외가 나고 구매자 차감과 거래내역이 모두 롤백된다")
    void rollsBackBuyerDebitWhenHoldingWalletMissing() {
        given(memberQuery.getHoldingMemberId()).willReturn(404L); // 지갑이 없는 회원 ID
        Long buyerId = 3L;
        saveWallet(buyerId, "10000");

        assertThatThrownBy(() -> paymentUseCase.pay(700L, buyerId, new BigDecimal("3000")))
                .isInstanceOf(BusinessException.class);

        assertThat(balanceOf(buyerId)).isEqualByComparingTo("10000");
        assertThat(balanceOf(HOLDING_ID)).isEqualByComparingTo("0");
        assertThat(walletTransactionRepository.count()).isZero();
    }

    @Test
    void allowsSameRefOnDifferentWallets() {
        Wallet buyerWallet = walletRepository.save(new Wallet(11L));
        Wallet escrowWallet = walletRepository.save(new Wallet(12L));

        walletTransactionRepository.saveAndFlush(
                WalletTransaction.paymentOut(buyerWallet, 900L, new BigDecimal("3000"), LocalDateTime.now()));
        walletTransactionRepository.saveAndFlush(
                WalletTransaction.paymentOut(escrowWallet, 900L, new BigDecimal("3000"), LocalDateTime.now()));

        assertThat(walletTransactionRepository.count()).isEqualTo(2);
    }

    @Test
    void rejectsDuplicateRefOnSameWallet() {
        Wallet buyerWallet = walletRepository.save(new Wallet(13L));
        walletTransactionRepository.saveAndFlush(
                WalletTransaction.paymentOut(buyerWallet, 901L, new BigDecimal("3000"), LocalDateTime.now()));

        assertThatThrownBy(() -> walletTransactionRepository.saveAndFlush(
                WalletTransaction.paymentOut(buyerWallet, 901L, new BigDecimal("3000"), LocalDateTime.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void saveWallet(Long memberId, String balance) {
        Wallet wallet = new Wallet(memberId);
        wallet.credit(new BigDecimal(balance));
        walletRepository.save(wallet);
    }

    private BigDecimal balanceOf(Long memberId) {
        return walletRepository.findByMemberId(memberId).orElseThrow().getBalance();
    }

    private WalletTransaction findTransaction(Long memberId, Long orderId) {
        Long walletId = walletRepository.findByMemberId(memberId).orElseThrow().getId();
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
        assertThat(balanceOf(HOLDING_ID))
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
                .findByMemberId(HOLDING_ID).orElseThrow();

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
        assertThat(balanceOf(HOLDING_ID))
                .isEqualByComparingTo("0");

        // 미리 저장한 거래내역 1건만 남아야 함
        assertThat(walletTransactionRepository.count()).isEqualTo(1);
    }
}
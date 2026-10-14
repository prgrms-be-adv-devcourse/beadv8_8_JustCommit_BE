package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.config.JpaAuditingConfig;
import com.justcommit.backend.payment.PaymentResult;
import com.justcommit.backend.payment.PaymentStatus;
import com.justcommit.backend.payment.PaymentUseCase;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

    @AfterEach
    void cleanUp() {
        walletTransactionRepository.deleteAllInBatch();
        walletRepository.deleteAllInBatch();
    }

    @Test
    void debitsOnlyOnceForConcurrentRequestsOfSameOrder() throws Exception {
        Long buyerId = 1L;
        saveWallet(buyerId, "10000");
        Long orderId = 500L;

        List<PaymentResult> results = runConcurrently(
                i -> () -> paymentUseCase.pay(orderId, buyerId, new BigDecimal("3000")));

        assertThat(results).filteredOn(r -> r.status() == PaymentStatus.PAID).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() == PaymentStatus.ALREADY_PAID).hasSize(THREADS - 1);
        assertThat(balanceOf(buyerId)).isEqualByComparingTo("7000");
        Long walletId = walletRepository.findByMemberId(buyerId).orElseThrow().getId();
        assertThat(walletTransactionRepository.existsByWallet_IdAndRefTypeAndRefId(
                walletId, RefType.PAYMENT, orderId)).isTrue();
        assertThat(walletTransactionRepository.count()).isEqualTo(1);
    }

    @Test
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
        assertThat(walletTransactionRepository.count()).isEqualTo(3);
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
}

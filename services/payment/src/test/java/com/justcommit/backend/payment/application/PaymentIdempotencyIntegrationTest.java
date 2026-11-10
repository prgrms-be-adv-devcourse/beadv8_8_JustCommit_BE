package com.justcommit.backend.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.justcommit.backend.payment.config.JpaAuditingConfig;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.Wallet;
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
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 내부 API는 같은 Idempotency-Key(업무 ID)로 다시 · 동시에 올 수 있다 (응답 타임아웃 뒤 재호출)
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
class PaymentIdempotencyIntegrationTest {

    private static final int THREADS = 8;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired PaymentService paymentService;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository walletTransactionRepository;

    @BeforeEach
    void setUpSystemWallets() {
        Wallet holding = Wallet.system(WalletType.HOLDING);
        holding.credit(new BigDecimal("100000"));
        walletRepository.save(holding);
        walletRepository.save(Wallet.system(WalletType.REVENUE));
    }

    @AfterEach
    void cleanUp() {
        walletTransactionRepository.deleteAllInBatch();
        walletRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("같은 settlementId로 동시에 지급해도 정산금과 수수료는 한 번만 적립된다")
    void paysOutOnlyOnceForConcurrentRequestsOfSameSettlement() throws Exception {
        Long sellerId = 40L;
        walletRepository.save(Wallet.forMember(sellerId));

        runConcurrently(i -> () -> {
            paymentService.distributeSettlement(1L, sellerId, new BigDecimal("9500"), new BigDecimal("500"));
            return null;
        });

        assertThat(balanceOf(sellerId)).isEqualByComparingTo("9500");
        assertThat(systemBalance(WalletType.REVENUE)).isEqualByComparingTo("500");
        assertThat(systemBalance(WalletType.HOLDING)).isEqualByComparingTo("90000");
        // HOLDING 출금 + 판매자 입금 + REVENUE 수수료 입금
        assertThat(walletTransactionRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 settlementId로 다시 지급하면 아무것도 하지 않는다 (판매자 지갑이 없던 첫 지급 포함)")
    void doesNothingWhenSameSettlementIsPaidAgain() {
        Long sellerId = 41L;

        paymentService.distributeSettlement(2L, sellerId, new BigDecimal("9500"), new BigDecimal("500"));
        paymentService.distributeSettlement(2L, sellerId, new BigDecimal("9500"), new BigDecimal("500"));

        assertThat(balanceOf(sellerId)).isEqualByComparingTo("9500");
        assertThat(systemBalance(WalletType.REVENUE)).isEqualByComparingTo("500");
        assertThat(systemBalance(WalletType.HOLDING)).isEqualByComparingTo("90000");
        assertThat(walletTransactionRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 orderItemId로 동시에 환불해도 한 번만 환불되고 나머지는 ALREADY_REFUNDED")
    void refundsOnlyOnceForConcurrentRequestsOfSameOrderItem() throws Exception {
        Long buyerId = 50L;
        Long orderItemId = 300L;
        walletRepository.save(Wallet.forMember(buyerId));

        List<PaymentResult> results = runConcurrently(
                i -> () -> paymentService.refundCancelledItem(orderItemId, buyerId, new BigDecimal("15000")));

        assertThat(results).filteredOn(r -> r.status() == PaymentStatus.REFUNDED).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() == PaymentStatus.ALREADY_REFUNDED).hasSize(THREADS - 1);

        assertThat(balanceOf(buyerId)).isEqualByComparingTo("15000");
        assertThat(systemBalance(WalletType.HOLDING)).isEqualByComparingTo("85000");
        // HOLDING 출금 + 구매자 입금
        assertThat(walletTransactionRepository.findAll())
                .hasSize(2)
                .allMatch(t -> t.getRefType() == RefType.ORDER_ITEM_CANCEL_REFUND && t.getRefId().equals(orderItemId));
    }

    private BigDecimal balanceOf(Long memberId) {
        return walletRepository.findByMemberId(memberId).orElseThrow().getBalance();
    }

    private BigDecimal systemBalance(WalletType type) {
        return walletRepository.findByWalletType(type).orElseThrow().getBalance();
    }

    private <T> List<T> runConcurrently(IntFunction<Callable<T>> taskFactory) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < THREADS; i++) {
                Callable<T> task = taskFactory.apply(i);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS)); // 예외가 나면 테스트 실패
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}

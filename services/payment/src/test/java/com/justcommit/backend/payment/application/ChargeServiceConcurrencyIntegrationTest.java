package com.justcommit.backend.payment.application;

import com.justcommit.backend.payment.config.JpaAuditingConfig;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.domain.Charge;
import com.justcommit.backend.payment.domain.ChargeStatus;
import com.justcommit.backend.payment.domain.ChargeType;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletType;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.infrastructure.pg.TossConfirmRejectedException;
import com.justcommit.backend.payment.infrastructure.pg.TossConfirmResponse;
import com.justcommit.backend.payment.infrastructure.pg.TossConfirmResultUnknownException;
import com.justcommit.backend.payment.infrastructure.pg.TossPaymentClient;
import com.justcommit.backend.payment.infrastructure.repository.ChargeRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;
import com.justcommit.backend.payment.presentation.dto.ChargeConfirmRequest;
import com.justcommit.backend.payment.presentation.dto.ChargeConfirmResponse;
import com.justcommit.backend.payment.infrastructure.crypto.PaymentKeyCipher;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.check_nullability=false"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ChargeService.class, ChargeConfirmFacade.class, PaymentService.class, WalletService.class, PaymentKeyCipher.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ChargeServiceConcurrencyIntegrationTest {

    private static final int THREADS = 8;
    private static final Long MEMBER_ID = 1L;
    private static final BigDecimal CHARGE_AMOUNT = new BigDecimal("10000");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired ChargeConfirmFacade chargeConfirmFacade;
    @Autowired PaymentService paymentUseCase;
    @Autowired ChargeRepository chargeRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository walletTransactionRepository;
    @Autowired PaymentKeyCipher paymentKeyCipher;

    @MockitoBean TossPaymentClient tossPaymentClient;

    @BeforeEach
    void setUp() {
        walletRepository.save(Wallet.system(WalletType.HOLDING));

        // 토스는 항상 요청한 값 그대로 승인. 실제 네트워크 지연처럼 약간 기다리게 해서 요청이 겹치도록 함
        given(tossPaymentClient.confirm(any(), any(), any())).willAnswer(inv -> {
            Thread.sleep(100);
            BigDecimal amount = inv.getArgument(2);
            return new TossConfirmResponse(
                    inv.getArgument(0), inv.getArgument(1), "DONE",
                    amount.longValue(), OffsetDateTime.now());
        });
    }

    @AfterEach
    void cleanUp() {
        walletTransactionRepository.deleteAllInBatch();
        chargeRepository.deleteAllInBatch();
        walletRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("같은 충전 건에 승인 요청이 동시에 와도 한 번만 적립되고 토스도 한 번만 호출된다")
    void confirmsOnlyOnceForSameCharge() throws Exception {
        Wallet wallet = saveWallet(MEMBER_ID, "0");
        Charge charge = saveCharge(wallet);
        ChargeConfirmRequest request = confirmRequest(charge);

        List<Object> results = runConcurrently(i ->
                () -> chargeConfirmFacade.confirmCharge(MEMBER_ID, charge.getId(), request));

        assertThat(results).filteredOn(r -> !(r instanceof Throwable)).hasSize(1);
        // 나머지는 승인 중이면 CHARGE_PROCESSING, 이미 끝났으면 CHARGE_ALREADY_PROCESSED
        assertThat(results).filteredOn(this::isDuplicateConfirm).hasSize(THREADS - 1);

        verify(tossPaymentClient, times(1)).confirm(any(), any(), any());
        assertThat(balanceOf(MEMBER_ID)).isEqualByComparingTo("10000");
        assertThat(walletTransactionRepository.count()).isEqualTo(1);
        Charge saved = chargeRepository.findById(charge.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ChargeStatus.APPROVED);
        assertThat(paymentKeyCipher.decrypt(saved.getPgPaymentKeyEnc()))
                .isEqualTo(request.getPaymentKey());
    }

    @Test
    @DisplayName("같은 회원의 다른 충전 건을 동시에 승인하면 모든 금액이 정확히 적립된다")
    void creditsAllWhenDifferentChargesConfirmConcurrently() throws Exception {
        Wallet wallet = saveWallet(MEMBER_ID, "0");
        List<Charge> charges = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            charges.add(saveCharge(wallet));
        }

        List<Object> results = runConcurrently(i -> () -> {
            Charge charge = charges.get(i);
            return chargeConfirmFacade.confirmCharge(MEMBER_ID, charge.getId(), confirmRequest(charge));
        });

        assertThat(results).noneMatch(r -> r instanceof Throwable);
        assertThat(balanceOf(MEMBER_ID)).isEqualByComparingTo(CHARGE_AMOUNT.multiply(BigDecimal.valueOf(THREADS)));
        assertThat(walletTransactionRepository.count()).isEqualTo(THREADS);
    }

    @Test
    @DisplayName("충전 승인과 주문 결제가 동시에 실행돼도 모두 성공하고 최종 잔액이 맞는다")
    void keepsBalanceConsistentWhenChargeAndPaymentRunConcurrently() throws Exception {
        Wallet wallet = saveWallet(MEMBER_ID, "10000");
        Charge charge = saveCharge(wallet);
        ChargeConfirmRequest request = confirmRequest(charge);

        // 0번 스레드는 충전 승인, 나머지 7개는 1,000원짜리 서로 다른 주문 결제
        List<Object> results = runConcurrently(i -> i == 0
                ? () -> chargeConfirmFacade.confirmCharge(MEMBER_ID, charge.getId(), request)
                : () -> paymentUseCase.pay(2000L + i, MEMBER_ID, new BigDecimal("1000")));

        assertThat(results).noneMatch(r -> r instanceof Throwable);
        assertThat(results).filteredOn(r -> r instanceof PaymentResult p && p.status() == PaymentStatus.PAID)
                .hasSize(THREADS - 1);

        // 10,000 + 충전 10,000 - 결제 7,000
        assertThat(balanceOf(MEMBER_ID)).isEqualByComparingTo("13000");
        assertThat(walletRepository.findByWalletType(WalletType.HOLDING).orElseThrow().getBalance()).isEqualByComparingTo("7000");
        assertThat(chargeRepository.findById(charge.getId()).orElseThrow().getStatus())
                .isEqualTo(ChargeStatus.APPROVED);
        // 충전 입금 1건 + 결제 7건 × (구매자 출금 + HOLDING 입금)
        assertThat(walletTransactionRepository.count()).isEqualTo(1 + (THREADS - 1) * 2);
    }

    private Wallet saveWallet(Long memberId, String balance) {
        Wallet wallet = Wallet.forMember(memberId);
        BigDecimal amount = new BigDecimal(balance);
        if (amount.signum() > 0) {
            wallet.credit(amount);
        }
        return walletRepository.save(wallet);
    }

    private Charge saveCharge(Wallet wallet) {
        return chargeRepository.save(Charge.ready(wallet, ChargeType.TOSS, CHARGE_AMOUNT));
    }

    private ChargeConfirmRequest confirmRequest(Charge charge) {
        ChargeConfirmRequest request = new ChargeConfirmRequest();
        ReflectionTestUtils.setField(request, "paymentKey", "pk-" + charge.getId());
        ReflectionTestUtils.setField(request, "orderId", charge.getPgOrderNo());
        ReflectionTestUtils.setField(request, "amount", charge.getAmount());
        return request;
    }

    private BigDecimal balanceOf(Long memberId) {
        return walletRepository.findByMemberId(memberId).orElseThrow().getBalance();
    }

    private boolean isDuplicateConfirm(Object result) {
        return result instanceof BusinessException e
                && (e.getErrorCode() == PaymentErrorCode.CHARGE_PROCESSING
                || e.getErrorCode() == PaymentErrorCode.CHARGE_ALREADY_PROCESSED);
    }

    private ChargeStatus statusOf(Charge charge) {
        return chargeRepository.findById(charge.getId()).orElseThrow().getStatus();
    }

    // 모든 스레드를 동시에 출발시키고, 정상 결과 또는 발생한 예외를 그대로 모아 반환
    private List<Object> runConcurrently(
            java.util.function.IntFunction<Callable<?>> taskFactory) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < THREADS; i++) {
                Callable<?> task = taskFactory.apply(i);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            List<Object> results = new ArrayList<>();
            for (Future<?> future : futures) {
                try {
                    results.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    results.add(e.getCause());
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }


    @Test
    @DisplayName("토스 승인 금액이 다르면 적립하지 않고 PENDING으로 남는다")
    void keepsPendingWhenTossAmountMismatches() {
        Wallet wallet = saveWallet(MEMBER_ID, "0");
        Charge charge = saveCharge(wallet);
        ChargeConfirmRequest request = confirmRequest(charge);

        // 토스는 DONE인데 승인 금액이 우리 기록(10,000원)과 다른 상황
        doReturn(new TossConfirmResponse(
                request.getPaymentKey(), request.getOrderId(), "DONE", 20000L, OffsetDateTime.now()
        )).when(tossPaymentClient).confirm(any(), any(), any());

        ChargeConfirmResponse response = chargeConfirmFacade.confirmCharge(MEMBER_ID, charge.getId(), request);

        assertThat(response.getStatus()).isEqualTo(ChargeStatus.PENDING);
        assertThat(statusOf(charge)).isEqualTo(ChargeStatus.PENDING);
        assertThat(balanceOf(MEMBER_ID)).isEqualByComparingTo("0");
        assertThat(walletTransactionRepository.count()).isZero();
    }

    @Test
    @DisplayName("토스가 승인을 거절하면 FAILED가 되고 실패 응답을 준다")
    void failsWhenTossRejects() {
        Wallet wallet = saveWallet(MEMBER_ID, "0");
        Charge charge = saveCharge(wallet);
        ChargeConfirmRequest request = confirmRequest(charge);

        doThrow(new TossConfirmRejectedException("REJECT_CARD_PAYMENT", "카드 승인이 거절되었습니다."))
                .when(tossPaymentClient).confirm(any(), any(), any());

        assertThatThrownBy(() -> chargeConfirmFacade.confirmCharge(MEMBER_ID, charge.getId(), request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.PG_CONFIRM_FAILED);

        assertThat(statusOf(charge)).isEqualTo(ChargeStatus.FAILED);
        assertThat(balanceOf(MEMBER_ID)).isEqualByComparingTo("0");
        assertThat(walletTransactionRepository.count()).isZero();
    }

    @Test
    @DisplayName("토스 승인 결과를 모르면 PENDING으로 남고 잔액은 그대로다")
    void keepsPendingWhenTossResultUnknown() {
        Wallet wallet = saveWallet(MEMBER_ID, "0");
        Charge charge = saveCharge(wallet);
        ChargeConfirmRequest request = confirmRequest(charge);

        doThrow(new TossConfirmResultUnknownException("timeout", new RuntimeException()))
                .when(tossPaymentClient).confirm(any(), any(), any());

        ChargeConfirmResponse response = chargeConfirmFacade.confirmCharge(MEMBER_ID, charge.getId(), request);

        assertThat(response.getStatus()).isEqualTo(ChargeStatus.PENDING);
        Charge saved = chargeRepository.findById(charge.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ChargeStatus.PENDING);
        // 복구 스케줄러(#363)가 재승인할 수 있도록 TX1에서 paymentKey가 저장돼 있어야 한다
        assertThat(paymentKeyCipher.decrypt(saved.getPgPaymentKeyEnc())).isEqualTo(request.getPaymentKey());
        assertThat(balanceOf(MEMBER_ID)).isEqualByComparingTo("0");
        assertThat(walletTransactionRepository.count()).isZero();
    }

    @Test
    @DisplayName("PENDING인 충전에 승인 요청이 다시 오면 처리 중 응답을 주고 토스를 호출하지 않는다")
    void rejectsConfirmWhilePending() {
        Wallet wallet = saveWallet(MEMBER_ID, "0");
        Charge charge = saveCharge(wallet);
        ChargeConfirmRequest request = confirmRequest(charge);

        doThrow(new TossConfirmResultUnknownException("timeout", new RuntimeException()))
                .when(tossPaymentClient).confirm(any(), any(), any());
        chargeConfirmFacade.confirmCharge(MEMBER_ID, charge.getId(), request);

        assertThatThrownBy(() -> chargeConfirmFacade.confirmCharge(MEMBER_ID, charge.getId(), request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.CHARGE_PROCESSING);

        verify(tossPaymentClient, times(1)).confirm(any(), any(), any());
        assertThat(statusOf(charge)).isEqualTo(ChargeStatus.PENDING);
    }

    @Test
    @DisplayName("토스 승인 후 DB 반영이 실패하면 PENDING으로 남고 이중 적립되지 않는다")
    void keepsPendingWhenApplyFailsAfterTossDone() {
        Wallet wallet = saveWallet(MEMBER_ID, "0");
        Charge charge = saveCharge(wallet);
        ChargeConfirmRequest request = confirmRequest(charge);

        // 같은 충전 건의 거래 내역이 이미 있어 TX2의 거래 내역 저장이 uk_wallet_transaction_ref에 걸리는 상황
        ReflectionTestUtils.setField(charge, "approvedAt", LocalDateTime.now());
        walletTransactionRepository.save(WalletTransaction.chargeIn(wallet, charge));

        ChargeConfirmResponse response = chargeConfirmFacade.confirmCharge(MEMBER_ID, charge.getId(), request);

        assertThat(response.getStatus()).isEqualTo(ChargeStatus.PENDING);
        assertThat(statusOf(charge)).isEqualTo(ChargeStatus.PENDING);
        // TX2 전체가 롤백돼 잔액이 늘지 않고, 거래 내역도 미리 넣은 1건뿐이다
        assertThat(balanceOf(MEMBER_ID)).isEqualByComparingTo("0");
        assertThat(walletTransactionRepository.count()).isEqualTo(1);
    }
}

package com.justcommit.backend.payment.application.recovery;

import com.justcommit.backend.payment.application.ChargeService;
import com.justcommit.backend.payment.application.WalletService;
import com.justcommit.backend.payment.config.ChargeRecoveryConfig;
import com.justcommit.backend.payment.config.JpaAuditingConfig;
import com.justcommit.backend.payment.domain.Charge;
import com.justcommit.backend.payment.domain.ChargeStatus;
import com.justcommit.backend.payment.domain.ChargeType;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.infrastructure.crypto.PaymentKeyCipher;
import com.justcommit.backend.payment.infrastructure.pg.TossLookupFailedException;
import com.justcommit.backend.payment.infrastructure.pg.TossLookupUnauthorizedException;
import com.justcommit.backend.payment.infrastructure.pg.TossPaymentClient;
import com.justcommit.backend.payment.infrastructure.pg.TossPaymentNotFoundException;
import com.justcommit.backend.payment.infrastructure.pg.TossPaymentResponse;
import com.justcommit.backend.payment.infrastructure.repository.ChargeRecoveryTarget;
import com.justcommit.backend.payment.infrastructure.repository.ChargeRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.check_nullability=false"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ChargeRecoveryConfig.class, ChargeRecoveryServiceIntegrationTest.MutableClockConfig.class,
        ChargeService.class, WalletService.class, PaymentKeyCipher.class,
        ChargeRecoveryService.class, ChargeRecoveryDecision.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ChargeRecoveryServiceIntegrationTest {

    private static final Long MEMBER_ID = 1L;
    private static final BigDecimal CHARGE_AMOUNT = new BigDecimal("10000");
    // 충전 생성(createdAt) 시각. 첫 조회는 PENDING 진입 5분 뒤
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 15, 10, 0);
    private static final LocalDateTime FIRST_LOOKUP_AT = T0.plusMinutes(5);
    private static final OffsetDateTime APPROVED_AT = OffsetDateTime.of(2026, 11, 15, 10, 1, 0, 0, ZoneOffset.ofHours(9));

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    // 테스트가 시각을 정해 가며 스케줄러를 실행한다
    static class MutableClock extends Clock {
        private volatile Instant instant = T0.atZone(ZoneId.systemDefault()).toInstant();

        void set(LocalDateTime time) {
            instant = time.atZone(ZoneId.systemDefault()).toInstant();
        }

        @Override public ZoneId getZone() { return ZoneId.systemDefault(); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock();
        }
    }

    @Autowired ChargeRecoveryService chargeRecoveryService;
    @Autowired ChargeService chargeService;
    @Autowired ChargeRepository chargeRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository walletTransactionRepository;
    @Autowired DataSource dataSource;
    @Autowired MutableClock clock;

    @MockitoBean TossPaymentClient tossPaymentClient;

    @AfterEach
    void cleanUp() {
        walletTransactionRepository.deleteAllInBatch();
        chargeRepository.deleteAllInBatch();
        walletRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("조회 실패가 3번 이어지면 5분 → 15분 → 60분 뒤에 조회하고 REVIEW_REQUIRED가 된다")
    void reviewsAfterThreeLookupFailures() {
        Charge charge = pendingCharge(saveWallet());
        given(tossPaymentClient.getPaymentByOrderId(any())).willThrow(lookupFailed());

        runAt(FIRST_LOOKUP_AT);
        assertRecoveryState(charge, ChargeStatus.PENDING, 1, FIRST_LOOKUP_AT.plusMinutes(15));

        // 다음 조회 시각 전에는 대상이 아니다
        runAt(FIRST_LOOKUP_AT.plusMinutes(14));
        verify(tossPaymentClient, times(1)).getPaymentByOrderId(any());

        LocalDateTime second = FIRST_LOOKUP_AT.plusMinutes(15);
        runAt(second);
        assertRecoveryState(charge, ChargeStatus.PENDING, 2, second.plusMinutes(60));

        runAt(second.plusMinutes(60));
        assertRecoveryState(charge, ChargeStatus.REVIEW_REQUIRED, 3, null);

        // REVIEW_REQUIRED가 된 뒤에는 더 조회하지 않는다
        runAt(second.plusDays(1));
        verify(tossPaymentClient, times(3)).getPaymentByOrderId(any());
        assertThat(balance()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("조회 실패 1번 뒤 IN_PROGRESS면 횟수는 그대로이고 15분 뒤 다시 조회한다")
    void keepsAttemptCountWhenInProgressAfterFailure() {
        Charge charge = pendingCharge(saveWallet());
        given(tossPaymentClient.getPaymentByOrderId(any()))
                .willThrow(lookupFailed())
                .willReturn(payment(charge, "IN_PROGRESS", null));

        runAt(FIRST_LOOKUP_AT);
        LocalDateTime second = FIRST_LOOKUP_AT.plusMinutes(15);
        runAt(second);

        assertRecoveryState(charge, ChargeStatus.PENDING, 1, second.plusMinutes(15));
    }

    @Test
    @DisplayName("404가 이어지면 createdAt + 60분 전까지 15분씩 미루고 횟수는 그대로, 이후 REVIEW_REQUIRED (FAILED 아님)")
    void reviewsNotFoundAfterReviewTime() {
        Charge charge = pendingCharge(saveWallet());
        given(tossPaymentClient.getPaymentByOrderId(any())).willThrow(new TossPaymentNotFoundException("404", null));

        // 5분 → 20분 → 35분 → 50분: 모두 createdAt + 60분 전
        LocalDateTime now = FIRST_LOOKUP_AT;
        for (int i = 0; i < 4; i++) {
            runAt(now);
            assertRecoveryState(charge, ChargeStatus.PENDING, 0, now.plusMinutes(15));
            now = now.plusMinutes(15);
        }

        // 65분: createdAt + 60분 경과
        runAt(now);
        assertRecoveryState(charge, ChargeStatus.REVIEW_REQUIRED, 0, null);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    @DisplayName("401, 403이 이어지면 createdAt + 60분이 지나도 PENDING, 횟수 그대로, 15분씩 미룬다")
    void keepsPendingOnUnauthorized(int status) {
        Charge charge = pendingCharge(saveWallet());
        given(tossPaymentClient.getPaymentByOrderId(any()))
                .willThrow(new TossLookupUnauthorizedException("status=" + status, null));

        LocalDateTime now = FIRST_LOOKUP_AT;
        for (int i = 0; i < 8; i++) {
            runAt(now);
            assertRecoveryState(charge, ChargeStatus.PENDING, 0, now.plusMinutes(15));
            now = now.plusMinutes(15);
        }
        assertThat(now).isAfter(T0.plusMinutes(60));
    }

    @Test
    @DisplayName("[S-P02] 토스 DONE이고 정보가 맞으면 APPROVED로 바꾸고 한 번 적립한다")
    void approvesDone() {
        Charge charge = pendingCharge(saveWallet());
        given(tossPaymentClient.getPaymentByOrderId(charge.getPgOrderNo()))
                .willReturn(payment(charge, "DONE", APPROVED_AT));

        runAt(FIRST_LOOKUP_AT);

        Charge saved = reload(charge);
        assertThat(saved.getStatus()).isEqualTo(ChargeStatus.APPROVED);
        assertThat(saved.getApprovedAt()).isEqualTo(APPROVED_AT.toLocalDateTime());
        assertThat(saved.getNextAttemptAt()).isNull();
        assertThat(balance()).isEqualByComparingTo("10000");
        assertThat(walletTransactionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("토스 DONE인데 금액이 다르면 적립하지 않고 REVIEW_REQUIRED")
    void reviewsDoneWithDifferentAmount() {
        Charge charge = pendingCharge(saveWallet());
        given(tossPaymentClient.getPaymentByOrderId(any())).willReturn(
                new TossPaymentResponse("pk", charge.getPgOrderNo(), "DONE", 20000L, APPROVED_AT));

        runAt(FIRST_LOOKUP_AT);

        assertRecoveryState(charge, ChargeStatus.REVIEW_REQUIRED, 0, null);
        assertThat(balance()).isEqualByComparingTo("0");
        assertThat(walletTransactionRepository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABORTED", "EXPIRED"})
    @DisplayName("[S-P02] 토스 ABORTED, EXPIRED면 FAILED")
    void failsAbortedOrExpired(String status) {
        Charge charge = pendingCharge(saveWallet());
        given(tossPaymentClient.getPaymentByOrderId(any())).willReturn(payment(charge, status, null));

        runAt(FIRST_LOOKUP_AT);

        assertRecoveryState(charge, ChargeStatus.FAILED, 0, null);
        assertThat(balance()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("조회하는 사이 사용자 승인이 먼저 반영되면(CHARGE_ALREADY_PROCESSED) 예외 없이 건너뛰고 한 번만 적립된다")
    void skipsAlreadyProcessedCharge() {
        Charge charge = pendingCharge(saveWallet());
        given(tossPaymentClient.getPaymentByOrderId(any())).willAnswer(inv -> {
            chargeService.completeConfirm(charge.getId(), APPROVED_AT.toLocalDateTime());
            return payment(charge, "DONE", APPROVED_AT);
        });

        runAt(FIRST_LOOKUP_AT);

        assertThat(reload(charge).getStatus()).isEqualTo(ChargeStatus.APPROVED);
        assertThat(balance()).isEqualByComparingTo("10000");
        assertThat(walletTransactionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("한 건에서 예외가 나도 나머지 건은 처리하고, 예외 난 건은 임대(2분)가 끝난 뒤 다시 잡힌다")
    void continuesBatchWhenOneChargeThrows() {
        Wallet wallet = saveWallet();
        Charge broken = pendingCharge(wallet);
        Charge ok = pendingCharge(wallet);
        given(tossPaymentClient.getPaymentByOrderId(broken.getPgOrderNo()))
                .willThrow(new IllegalStateException("예상 못 한 오류"));
        given(tossPaymentClient.getPaymentByOrderId(ok.getPgOrderNo()))
                .willReturn(payment(ok, "DONE", APPROVED_AT));

        runAt(FIRST_LOOKUP_AT);

        assertThat(reload(ok).getStatus()).isEqualTo(ChargeStatus.APPROVED);
        assertRecoveryState(broken, ChargeStatus.PENDING, 0, FIRST_LOOKUP_AT.plusMinutes(2));

        runAt(FIRST_LOOKUP_AT.plusMinutes(1));
        verify(tossPaymentClient, times(1)).getPaymentByOrderId(broken.getPgOrderNo());
        runAt(FIRST_LOOKUP_AT.plusMinutes(2));
        verify(tossPaymentClient, times(2)).getPaymentByOrderId(broken.getPgOrderNo());
    }

    @Test
    @DisplayName("REVIEW_REQUIRED 충전은 다음 조회 시각이 남아 있어도 대상에서 빠진다")
    void excludesReviewRequiredCharge() {
        Charge charge = pendingCharge(saveWallet());
        jdbc().update("update charge set status = 'REVIEW_REQUIRED' where id = ?", charge.getId());

        runAt(FIRST_LOOKUP_AT.plusDays(1));

        verify(tossPaymentClient, never()).getPaymentByOrderId(any());
    }

    @Test
    @DisplayName("사용자 승인의 completeConfirm과 스케줄러의 completeConfirm이 동시에 실행돼도 한 번만 적립된다")
    void creditsOnceWhenUserAndSchedulerCompleteConcurrently() throws Exception {
        Charge charge = pendingCharge(saveWallet());
        clock.set(FIRST_LOOKUP_AT);
        given(tossPaymentClient.getPaymentByOrderId(eq(charge.getPgOrderNo()))).willAnswer(inv -> {
            Thread.sleep(50);
            return payment(charge, "DONE", APPROVED_AT);
        });

        List<Object> results = runConcurrently(List.of(
                () -> chargeService.completeConfirm(charge.getId(), APPROVED_AT.toLocalDateTime()),
                () -> chargeRecoveryService.recoverDue()));

        // 스케줄러는 CHARGE_ALREADY_PROCESSED를 삼키므로 사용자 쪽만 예외가 날 수 있다
        assertThat(results).filteredOn(r -> !(r instanceof Throwable)).hasSizeGreaterThanOrEqualTo(1);
        assertThat(reload(charge).getStatus()).isEqualTo(ChargeStatus.APPROVED);
        assertThat(balance()).isEqualByComparingTo("10000");
        assertThat(walletTransactionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("APPROVED 충전에는 deferRecovery, markReviewRequired가 아무것도 바꾸지 않는다")
    void doesNotChangeApprovedCharge() {
        Charge charge = pendingCharge(saveWallet());
        chargeService.completeConfirm(charge.getId(), APPROVED_AT.toLocalDateTime());
        ChargeRecoveryTarget target = target(charge, 0, null);

        assertThat(chargeService.deferRecovery(target, 1, FIRST_LOOKUP_AT.plusMinutes(15))).isFalse();
        assertThat(chargeService.markReviewRequired(target, 1)).isFalse();

        assertRecoveryState(charge, ChargeStatus.APPROVED, 0, null);
    }

    @Test
    @DisplayName("attempt_count가 확보할 때와 다르면 deferRecovery, markReviewRequired가 0건 업데이트다")
    void ignoresUpdateWhenAttemptCountChanged() {
        Charge charge = pendingCharge(saveWallet());
        ChargeRecoveryTarget changed = target(charge, 1, FIRST_LOOKUP_AT);

        assertThat(chargeService.deferRecovery(changed, 2, FIRST_LOOKUP_AT.plusMinutes(60))).isFalse();
        assertThat(chargeService.markReviewRequired(changed, 3)).isFalse();

        assertRecoveryState(charge, ChargeStatus.PENDING, 0, FIRST_LOOKUP_AT);
        assertThat(chargeService.deferRecovery(target(charge, 0, FIRST_LOOKUP_AT), 1, FIRST_LOOKUP_AT.plusMinutes(15)))
                .isTrue();
    }

    @Test
    @DisplayName("임대가 끝나 다른 인스턴스가 다시 잡은 건에는 늦게 끝난 쪽의 반영이 0건이다")
    void ignoresStaleLease() {
        Charge charge = pendingCharge(saveWallet());
        clock.set(FIRST_LOOKUP_AT);
        ChargeRecoveryTarget first = chargeService.claimNextRecoveryTarget().orElseThrow();
        assertThat(first.leasedUntil()).isEqualTo(FIRST_LOOKUP_AT.plusMinutes(2));

        // 처음 잡은 쪽이 2분 넘게 끝나지 않아 다른 인스턴스가 다시 잡음
        clock.set(FIRST_LOOKUP_AT.plusMinutes(3));
        ChargeRecoveryTarget second = chargeService.claimNextRecoveryTarget().orElseThrow();

        assertThat(chargeService.deferRecovery(first, 1, FIRST_LOOKUP_AT.plusMinutes(20))).isFalse();
        assertThat(chargeService.markReviewRequired(first, 3)).isFalse();
        assertThat(chargeService.deferRecovery(second, 1, FIRST_LOOKUP_AT.plusMinutes(18))).isTrue();
        assertRecoveryState(charge, ChargeStatus.PENDING, 1, FIRST_LOOKUP_AT.plusMinutes(18));
    }

    @Test
    @DisplayName("확보한 인스턴스가 처리하지 못하고 죽어도 임대(2분)가 끝나면 다시 대상이 된다")
    void reclaimsAfterLeaseExpires() {
        Charge charge = pendingCharge(saveWallet());
        given(tossPaymentClient.getPaymentByOrderId(any())).willReturn(payment(charge, "IN_PROGRESS", null));
        clock.set(FIRST_LOOKUP_AT);
        chargeService.claimNextRecoveryTarget().orElseThrow();

        runAt(FIRST_LOOKUP_AT.plusMinutes(1));
        verify(tossPaymentClient, never()).getPaymentByOrderId(any());

        runAt(FIRST_LOOKUP_AT.plusMinutes(2));
        verify(tossPaymentClient, times(1)).getPaymentByOrderId(any());
        assertRecoveryState(charge, ChargeStatus.PENDING, 0, FIRST_LOOKUP_AT.plusMinutes(17));
    }

    @Test
    @DisplayName("스케줄러 두 개가 동시에 돌아도 SKIP LOCKED로 나눠 가져가 건마다 토스를 한 번만 조회한다")
    void splitsChargesBetweenConcurrentRuns() throws Exception {
        Wallet wallet = saveWallet();
        List<Charge> charges = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            charges.add(pendingCharge(wallet));
        }
        clock.set(FIRST_LOOKUP_AT);
        given(tossPaymentClient.getPaymentByOrderId(any())).willAnswer(inv -> {
            Thread.sleep(50);
            return new TossPaymentResponse("pk", inv.getArgument(0), "IN_PROGRESS", CHARGE_AMOUNT.longValue(), null);
        });

        List<Object> results = runConcurrently(List.of(
                () -> chargeRecoveryService.recoverDue(),
                () -> chargeRecoveryService.recoverDue()));

        assertThat(results).allMatch(r -> r instanceof Integer);
        assertThat(results.stream().mapToInt(r -> (Integer) r).sum()).isEqualTo(charges.size());
        for (Charge charge : charges) {
            verify(tossPaymentClient, times(1)).getPaymentByOrderId(charge.getPgOrderNo());
            assertRecoveryState(charge, ChargeStatus.PENDING, 0, FIRST_LOOKUP_AT.plusMinutes(15));
        }
    }

    private Wallet saveWallet() {
        return walletRepository.save(Wallet.forMember(MEMBER_ID));
    }

    // 사용자 승인 TX1을 거쳐 결과 불명으로 남은 충전 (생성 T0, 첫 조회 T0 + 5분)
    private Charge pendingCharge(Wallet wallet) {
        Charge charge = Charge.ready(wallet, ChargeType.TOSS, CHARGE_AMOUNT);
        charge.startConfirm("encrypted-key", FIRST_LOOKUP_AT);
        Charge saved = chargeRepository.save(charge);
        // createdAt은 감사 기능이 실제 시각으로 채우므로 고정 시각으로 맞춘다
        jdbc().update("update charge set created_at = ? where id = ?", T0, saved.getId());
        return saved;
    }

    private void runAt(LocalDateTime time) {
        clock.set(time);
        chargeRecoveryService.recoverDue();
    }

    private ChargeRecoveryTarget target(Charge charge, int attemptCount, LocalDateTime leasedUntil) {
        return new ChargeRecoveryTarget(
                charge.getId(), charge.getPgOrderNo(), charge.getAmount(), T0, attemptCount, leasedUntil);
    }

    private TossPaymentResponse payment(Charge charge, String status, OffsetDateTime approvedAt) {
        return new TossPaymentResponse("pk", charge.getPgOrderNo(), status, CHARGE_AMOUNT.longValue(), approvedAt);
    }

    private static TossLookupFailedException lookupFailed() {
        return new TossLookupFailedException("timeout", null);
    }

    private void assertRecoveryState(Charge charge, ChargeStatus status, int attemptCount, LocalDateTime nextAttemptAt) {
        Charge saved = reload(charge);
        assertThat(saved.getStatus()).isEqualTo(status);
        assertThat(saved.getAttemptCount()).isEqualTo(attemptCount);
        assertThat(saved.getNextAttemptAt()).isEqualTo(nextAttemptAt);
    }

    private Charge reload(Charge charge) {
        return chargeRepository.findById(charge.getId()).orElseThrow();
    }

    private BigDecimal balance() {
        return walletRepository.findByMemberId(MEMBER_ID).orElseThrow().getBalance();
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    // 모든 작업을 동시에 출발시키고, 정상 결과 또는 발생한 예외를 그대로 모아 반환
    private List<Object> runConcurrently(List<Callable<?>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (Callable<?> task : tasks) {
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
}

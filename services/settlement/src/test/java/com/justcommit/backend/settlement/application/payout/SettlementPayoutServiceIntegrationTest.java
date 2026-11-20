package com.justcommit.backend.settlement.application.payout;

import com.justcommit.backend.common.client.exception.InternalApiErrorException;
import com.justcommit.backend.common.client.exception.InternalApiResultUnknownException;
import com.justcommit.backend.common.client.exception.InternalApiUnavailableException;
import com.justcommit.backend.settlement.client.payment.SettlementPayoutClient;
import com.justcommit.backend.settlement.domain.Settlement;
import com.justcommit.backend.settlement.domain.SettlementStatus;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 실제 PostgreSQL로 정산 지급 스케줄러의 확보(SKIP LOCKED + 임대) · 호출 · 결과 반영을 확인 (payment 호출만 목)
// 스케줄러는 끄고 서비스를 직접 부른다 (백그라운드 실행이 테스트 데이터를 먼저 집지 않게)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class SettlementPayoutServiceIntegrationTest {

    private static final String GROUP = "internal-payment";
    private static final long SELLER_ID = 7L;

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
        registry.add("jwt.public-key", SettlementPayoutServiceIntegrationTest::newPublicKey);
        registry.add("internal-api.key", () -> "test-internal-key");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("custom.settlement.payout.enabled", () -> "false");
    }

    @Autowired SettlementPayoutService payoutService;
    @Autowired SettlementRepository settlementRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @MockitoBean SettlementPayoutClient payoutClient;

    @BeforeEach
    void cleanUp() {
        settlementRepository.deleteAll();
    }

    @Test
    @DisplayName("지급 대상 PENDING 정산은 한 번 실행에 지급 요청되고 COMPLETED + completed_at이 된다")
    void pendingSettlementIsPaidAndCompleted() {
        Settlement settlement = pending(1L, 1);

        assertThat(payoutService.payoutDue()).isEqualTo(1);

        verify(payoutClient).distributeSettlement(eq(settlement.getId()), eq(SELLER_ID), eq(1L),
                amount("28500"), amount("1500"), amount("3000"), amount("0"));
        Settlement paid = reload(settlement);
        assertThat(paid.getStatus()).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(paid.getCompletedAt()).isNotNull();
        assertThat(paid.getNextAttemptAt()).isNull();
        assertThat(paid.getLastError()).isNull();
        assertThat(paid.getAttemptCount()).isZero();
    }

    @Test
    @DisplayName("일부 반품 정산은 판매자 주문 · 배송비 · 부담금을 담아 지급 요청한다")
    void requestsWithSellerBurden() {
        Settlement settlement = save(Settlement.createSettlement(1L, SELLER_ID, new BigDecimal("30000"),
                new BigDecimal("3000"), new BigDecimal("6000"), LocalDateTime.now().minusMinutes(1), "몬스테라"));

        payoutService.payoutDue();

        verify(payoutClient).distributeSettlement(eq(settlement.getId()), eq(SELLER_ID), eq(1L),
                amount("22500"), amount("1500"), amount("3000"), amount("6000"));
        assertThat(reload(settlement).getStatus()).isEqualTo(SettlementStatus.COMPLETED);
    }

    @Test
    @DisplayName("정산금이 0원 · 음수여도 지급 요청이 나간다")
    void requestsZeroAndNegativePayout() {
        Settlement zero = save(Settlement.createSettlement(1L, SELLER_ID, new BigDecimal("30000"),
                new BigDecimal("3000"), new BigDecimal("28500"), LocalDateTime.now().minusMinutes(2), "0원"));
        Settlement negative = save(Settlement.createSettlement(2L, SELLER_ID, new BigDecimal("10000"),
                new BigDecimal("3000"), new BigDecimal("12000"), LocalDateTime.now().minusMinutes(1), "음수"));

        assertThat(payoutService.payoutDue()).isEqualTo(2);

        verify(payoutClient).distributeSettlement(eq(zero.getId()), eq(SELLER_ID), eq(1L),
                amount("0"), amount("1500"), amount("3000"), amount("28500"));
        verify(payoutClient).distributeSettlement(eq(negative.getId()), eq(SELLER_ID), eq(2L),
                amount("-2500"), amount("500"), amount("3000"), amount("12000"));
    }

    @Test
    @DisplayName("배송비가 없는 정산(옛 토픽으로만 받음)은 배송비 0으로 지급 요청한다")
    void requestsZeroShippingFeeWhenAbsent() {
        Settlement settlement = save(Settlement.createSettlement(1L, SELLER_ID, new BigDecimal("30000"), null,
                BigDecimal.ZERO, LocalDateTime.now().minusMinutes(1), "몬스테라"));

        payoutService.payoutDue();

        verify(payoutClient).distributeSettlement(eq(settlement.getId()), eq(SELLER_ID), eq(1L),
                amount("28500"), amount("1500"), amount("0"), amount("0"));
    }

    @Test
    @DisplayName("payment 호출 중에는 DB 트랜잭션이 없고, 다른 연결이 정산 행을 바로(NOWAIT) 잠글 수 있다")
    void noTransactionOrRowLockDuringPaymentCall() {
        Settlement settlement = pending(1L, 1);
        AtomicBoolean transactionActive = new AtomicBoolean(true);
        AtomicBoolean rowLockable = new AtomicBoolean(false);
        doAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            // 트랜잭션 밖이라 자동 커밋 연결에서 실행: 행 잠금이 남아 있으면 NOWAIT가 바로 예외
            Long locked = jdbcTemplate.queryForObject(
                    "SELECT id FROM settlement WHERE id = ? FOR UPDATE NOWAIT", Long.class, settlement.getId());
            rowLockable.set(settlement.getId().equals(locked));
            return null;
        }).when(payoutClient).distributeSettlement(anyLong(), anyLong(), anyLong(), any(), any(), any(), any());

        payoutService.payoutDue();

        assertThat(transactionActive).isFalse();
        assertThat(rowLockable).isTrue();
        assertThat(reload(settlement).getStatus()).isEqualTo(SettlementStatus.COMPLETED);
    }

    @Test
    @DisplayName("실패하면 FAILED로 남고 다음 시도 시각이 10초 → 30초 → 1분 뒤가 되며, 그 시각이 지나면 다시 요청해 성공한다")
    void failureBacksOffAndRetriesUntilSuccess() {
        Settlement settlement = pending(1L, 1);

        // 1번째 실패: 응답 타임아웃(결과 불명)
        doThrow(new InternalApiResultUnknownException(GROUP, null))
                .when(payoutClient).distributeSettlement(anyLong(), anyLong(), anyLong(), any(), any(), any(), any());
        assertFailure(settlement, 1, Duration.ofSeconds(10), "RESULT_UNKNOWN");

        // 다음 시도 시각 전에는 다시 요청하지 않음
        assertThat(payoutService.payoutDue()).isZero();
        verify(payoutClient, times(1)).distributeSettlement(anyLong(), anyLong(), anyLong(), any(), any(), any(), any());

        // 2번째 실패: payment 4xx 업무 오류도 같은 방식으로 다시 시도
        makeDue(settlement);
        doThrow(new InternalApiErrorException(GROUP, 400, "INVALID_AMOUNT", "금액이 올바르지 않습니다."))
                .when(payoutClient).distributeSettlement(anyLong(), anyLong(), anyLong(), any(), any(), any(), any());
        assertFailure(settlement, 2, Duration.ofSeconds(30), "HTTP_400 INVALID_AMOUNT");

        // 3번째 실패: 연결 실패, 이후로는 1분 고정
        makeDue(settlement);
        doThrow(new InternalApiUnavailableException(GROUP, null))
                .when(payoutClient).distributeSettlement(anyLong(), anyLong(), anyLong(), any(), any(), any(), any());
        assertFailure(settlement, 3, Duration.ofMinutes(1), "CONNECTION_FAILED");

        makeDue(settlement);
        assertFailure(settlement, 4, Duration.ofMinutes(1), "CONNECTION_FAILED");

        // 성공: 같은 settlementId로 다시 요청 (payment 멱등)
        makeDue(settlement);
        doNothing().when(payoutClient).distributeSettlement(anyLong(), anyLong(), anyLong(), any(), any(), any(), any());
        assertThat(payoutService.payoutDue()).isEqualTo(1);
        Settlement paid = reload(settlement);
        assertThat(paid.getStatus()).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(paid.getLastError()).isNull();
        assertThat(paid.getNextAttemptAt()).isNull();
        verify(payoutClient, times(5)).distributeSettlement(eq(settlement.getId()), anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("다음 시도 시각 전인 정산과 COMPLETED 정산은 집지 않는다")
    void skipsNotDueAndCompleted() {
        save(Settlement.createSettlement(1L, SELLER_ID, new BigDecimal("30000"), null, BigDecimal.ZERO,
                LocalDateTime.now().plusMinutes(1), "아직"));
        Settlement completed = pending(2L, 1);
        transactionTemplate.executeWithoutResult(status ->
                settlementRepository.findById(completed.getId()).orElseThrow().complete(LocalDateTime.now()));

        assertThat(payoutService.payoutDue()).isZero();
        verify(payoutClient, never()).distributeSettlement(anyLong(), anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("한 건이 실패해도 같은 회차의 다른 건은 지급된다")
    void oneFailureDoesNotAffectOthers() {
        Settlement failing = pending(1L, 2);
        Settlement other = pending(2L, 1);
        doThrow(new IllegalStateException("예상 밖 오류"))
                .when(payoutClient).distributeSettlement(eq(failing.getId()), anyLong(), anyLong(), any(), any(), any(), any());

        assertThat(payoutService.payoutDue()).isEqualTo(2);

        assertThat(reload(failing).getStatus()).isEqualTo(SettlementStatus.FAILED);
        assertThat(reload(failing).getLastError()).startsWith("IllegalStateException");
        assertThat(reload(other).getStatus()).isEqualTo(SettlementStatus.COMPLETED);
    }

    @Test
    @DisplayName("확보하면 임대가 커밋돼, 임대가 끝나기 전에는 다른 실행이 같은 건을 다시 집지 않는다")
    void claimedSettlementIsLeased() {
        Settlement settlement = pending(1L, 1);

        LocalDateTime before = LocalDateTime.now();
        PayoutTarget claimed = payoutService.claimNext().orElseThrow();

        assertThat(claimed.settlementId()).isEqualTo(settlement.getId());
        assertThat(reload(settlement).getNextAttemptAt()).isEqualTo(claimed.leasedUntil());
        assertThat(claimed.leasedUntil()).isAfterOrEqualTo(before.plusSeconds(30).truncatedTo(ChronoUnit.MICROS));
        assertThat(payoutService.claimNext()).isEmpty();
    }

    @Test
    @DisplayName("다른 실행이 잠근 정산은 SKIP LOCKED로 건너뛰고 다음 건을 집는다")
    void skipsRowLockedByAnotherExecution() throws Exception {
        Settlement first = pending(1L, 2);
        Settlement second = pending(2L, 1);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Long> holder = executor.submit(() -> transactionTemplate.execute(status -> {
                Long id = settlementRepository.findNextPayoutTargetForUpdate(LocalDateTime.now())
                        .orElseThrow().getId();
                locked.countDown();
                await(release);
                return id;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Optional<PayoutTarget> claimed = payoutService.claimNext();

            release.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS)).isEqualTo(first.getId());
            assertThat(claimed).map(PayoutTarget::settlementId).contains(second.getId());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("[S-G04] 두 실행(파드 2개)이 동시에 돌아도 정산마다 지급 요청은 한 번이다")
    void concurrentExecutionsRequestEachSettlementOnce() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= 20; i++) {
            ids.add(pending(i, 1).getId());
        }
        Map<Long, AtomicInteger> calls = new ConcurrentHashMap<>();
        doAnswer(invocation -> {
            calls.computeIfAbsent(invocation.getArgument(0), id -> new AtomicInteger()).incrementAndGet();
            Thread.sleep(20);
            return null;
        }).when(payoutClient).distributeSettlement(anyLong(), anyLong(), anyLong(), any(), any(), any(), any());

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> runs = List.of(
                    executor.submit(() -> {
                        await(start);
                        return payoutService.payoutDue();
                    }),
                    executor.submit(() -> {
                        await(start);
                        return payoutService.payoutDue();
                    }));
            start.countDown();
            int processed = 0;
            for (Future<Integer> run : runs) {
                processed += run.get(30, TimeUnit.SECONDS);
            }

            assertThat(processed).isEqualTo(20);
        } finally {
            executor.shutdownNow();
        }
        assertThat(calls).containsOnlyKeys(ids);
        assertThat(calls.values()).allSatisfy(count -> assertThat(count).hasValue(1));
        assertThat(settlementRepository.findAll())
                .allSatisfy(s -> assertThat(s.getStatus()).isEqualTo(SettlementStatus.COMPLETED));
    }

    @Test
    @DisplayName("호출 중 임대가 끝나 다른 실행이 다시 잡았으면 늦게 온 실패는 반영하지 않는다")
    void lateFailureDoesNotOverwriteNewLease() {
        Settlement settlement = pending(1L, 1);
        LocalDateTime otherLease = LocalDateTime.now().plusMinutes(5).truncatedTo(ChronoUnit.MICROS);
        doAnswer(invocation -> {
            jdbcTemplate.update("UPDATE settlement SET next_attempt_at = ? WHERE id = ?",
                    Timestamp.valueOf(otherLease), settlement.getId());
            throw new InternalApiResultUnknownException(GROUP, null);
        }).when(payoutClient).distributeSettlement(anyLong(), anyLong(), anyLong(), any(), any(), any(), any());

        payoutService.payoutDue();

        Settlement after = reload(settlement);
        assertThat(after.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(after.getAttemptCount()).isZero();
        assertThat(after.getNextAttemptAt()).isEqualTo(otherLease);
        assertThat(after.getLastError()).isNull();
    }

    @Test
    @DisplayName("호출 중 다른 실행이 지급 완료로 바꿨으면 늦게 온 실패는 COMPLETED를 되돌리지 않는다")
    void lateFailureDoesNotRevertCompleted() {
        Settlement settlement = pending(1L, 1);
        doAnswer(invocation -> {
            jdbcTemplate.update("""
                    UPDATE settlement SET status = 'COMPLETED', completed_at = now(), next_attempt_at = NULL
                     WHERE id = ?""", settlement.getId());
            throw new InternalApiResultUnknownException(GROUP, null);
        }).when(payoutClient).distributeSettlement(anyLong(), anyLong(), anyLong(), any(), any(), any(), any());

        payoutService.payoutDue();

        Settlement after = reload(settlement);
        assertThat(after.getStatus()).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(after.getAttemptCount()).isZero();
    }

    @Test
    @DisplayName("지급 지연 감시 쿼리: 생성 시각이 기준보다 이른 미지급 정산만 세고, 가장 오래된 건을 찾는다")
    void delayQueries() {
        Settlement old = pending(1L, 1);
        pending(2L, 1);
        Settlement oldCompleted = pending(3L, 1);
        transactionTemplate.executeWithoutResult(status ->
                settlementRepository.findById(oldCompleted.getId()).orElseThrow().complete(LocalDateTime.now()));
        jdbcTemplate.update("UPDATE settlement SET created_at = now() - interval '11 minutes' WHERE id IN (?, ?)",
                old.getId(), oldCompleted.getId());

        long delayed = settlementRepository.countByStatusInAndCreatedAtBefore(
                SettlementPayoutDelayMonitor.UNFINISHED, LocalDateTime.now().minusMinutes(10));

        assertThat(delayed).isEqualTo(1);
        assertThat(settlementRepository.findFirstByStatusInOrderByCreatedAtAsc(SettlementPayoutDelayMonitor.UNFINISHED))
                .map(Settlement::getId).contains(old.getId());
    }

    private void assertFailure(Settlement settlement, int attempts, Duration delay, String errorPrefix) {
        LocalDateTime before = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(payoutService.payoutDue()).isEqualTo(1);
        LocalDateTime after = LocalDateTime.now();

        Settlement failed = reload(settlement);
        assertThat(failed.getStatus()).isEqualTo(SettlementStatus.FAILED);
        assertThat(failed.getAttemptCount()).isEqualTo(attempts);
        assertThat(failed.getLastError()).startsWith(errorPrefix);
        assertThat(failed.getCompletedAt()).isNull();
        assertThat(failed.getNextAttemptAt()).isBetween(before.plus(delay), after.plus(delay));
    }

    // 다음 시도 시각을 지난 시각으로 당겨 바로 지급 대상이 되게 함
    private void makeDue(Settlement settlement) {
        jdbcTemplate.update("UPDATE settlement SET next_attempt_at = now() - interval '1 second' WHERE id = ?",
                settlement.getId());
    }

    // minutesAgo분 전부터 지급 대상인 PENDING 정산 (판매액 30000 → 수수료 1500, 정산금 28500)
    private Settlement pending(long sellerOrderId, int minutesAgo) {
        return save(Settlement.createSettlement(sellerOrderId, SELLER_ID, new BigDecimal("30000"),
                new BigDecimal("3000"), BigDecimal.ZERO, LocalDateTime.now().minusMinutes(minutesAgo), "몬스테라 외 1건"));
    }

    // 금액은 scale과 상관없이 값으로 비교
    private static BigDecimal amount(String expected) {
        return argThat(actual -> actual != null && actual.compareTo(new BigDecimal(expected)) == 0);
    }

    private Settlement save(Settlement settlement) {
        return settlementRepository.save(settlement);
    }

    private Settlement reload(Settlement settlement) {
        return settlementRepository.findById(settlement.getId()).orElseThrow();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String newPublicKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

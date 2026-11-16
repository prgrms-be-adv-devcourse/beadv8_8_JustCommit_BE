package com.justcommit.backend.payment.application.recovery;

import com.justcommit.backend.payment.application.recovery.ChargeRecoveryDecision.LookupResult;
import com.justcommit.backend.payment.application.recovery.ChargeRecoveryDecision.Outcome;
import com.justcommit.backend.payment.infrastructure.pg.TossPaymentResponse;
import com.justcommit.backend.payment.infrastructure.repository.ChargeRecoveryTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ChargeRecoveryDecisionTest {

    private static final String ORDER_ID = "CHG-order";
    private static final BigDecimal AMOUNT = new BigDecimal("10000");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 11, 15, 10, 0);
    // createdAt + 60분 (장기 미확정 수동 확인 기준)
    private static final LocalDateTime REVIEW_AT = CREATED_AT.plusMinutes(60);
    private static final LocalDateTime NOW = CREATED_AT.plusMinutes(5);
    private static final OffsetDateTime APPROVED_AT = OffsetDateTime.of(2026, 11, 15, 10, 1, 0, 0, ZoneOffset.ofHours(9));

    private final ChargeRecoveryDecision decision = new ChargeRecoveryDecision(new ChargeRecoveryProperties(
            Duration.ofMinutes(5), 100, Duration.ofMinutes(5),
            List.of(Duration.ofMinutes(15), Duration.ofMinutes(60)), 3,
            Duration.ofMinutes(15), Duration.ofMinutes(60), Duration.ofMinutes(15), Duration.ofMinutes(2)));

    @Test
    @DisplayName("DONE이고 orderId, 금액이 맞고 approvedAt이 있으면 APPROVE (횟수 변화 없음)")
    void approvesMatchingDone() {
        Outcome outcome = decide(found("DONE", ORDER_ID, 10000L, APPROVED_AT), charge(1), NOW);

        assertThat(outcome).isEqualTo(new Outcome.Approve(APPROVED_AT.toLocalDateTime()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABORTED", "EXPIRED"})
    @DisplayName("ABORTED, EXPIRED면 FAIL")
    void failsOnAbortedOrExpired(String status) {
        Outcome outcome = decide(found(status, ORDER_ID, 10000L, null), charge(0), NOW);

        assertThat(outcome).isEqualTo(new Outcome.Fail(status));
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_PROGRESS", "READY"})
    @DisplayName("IN_PROGRESS, READY이고 createdAt + 60분 직전이면 15분 뒤 재조회, 횟수 유지")
    void retriesUnapprovedBeforeReviewTime(String status) {
        LocalDateTime now = REVIEW_AT.minusNanos(1_000);

        Outcome outcome = decide(found(status, ORDER_ID, 10000L, null), charge(1), now);

        assertThat(outcome).isEqualTo(new Outcome.RetryLater(now.plusMinutes(15), 1, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_PROGRESS", "READY"})
    @DisplayName("IN_PROGRESS, READY이고 createdAt + 60분이 되면 REVIEW_REQUIRED, 횟수 유지 (FAIL 아님)")
    void reviewsUnapprovedAtReviewTime(String status) {
        assertThat(decide(found(status, ORDER_ID, 10000L, null), charge(1), REVIEW_AT))
                .isInstanceOfSatisfying(Outcome.ReviewRequired.class,
                        review -> assertThat(review.attemptCount()).isEqualTo(1));
        assertThat(decide(found(status, ORDER_ID, 10000L, null), charge(1), REVIEW_AT.plusMinutes(1)))
                .isInstanceOf(Outcome.ReviewRequired.class);
    }

    @Test
    @DisplayName("404이고 createdAt + 60분 직전이면 15분 뒤 재조회, 횟수 유지")
    void retriesNotFoundBeforeReviewTime() {
        LocalDateTime now = REVIEW_AT.minusNanos(1_000);

        Outcome outcome = decide(new LookupResult.NotFound(), charge(0), now);

        assertThat(outcome).isEqualTo(new Outcome.RetryLater(now.plusMinutes(15), 0, false));
    }

    @Test
    @DisplayName("404이고 createdAt + 60분이 되면 REVIEW_REQUIRED, 횟수 유지 (FAIL 아님)")
    void reviewsNotFoundAtReviewTime() {
        assertThat(decide(new LookupResult.NotFound(), charge(2), REVIEW_AT))
                .isInstanceOfSatisfying(Outcome.ReviewRequired.class,
                        review -> assertThat(review.attemptCount()).isEqualTo(2));
        assertThat(decide(new LookupResult.NotFound(), charge(2), REVIEW_AT.plusMinutes(1)))
                .isInstanceOf(Outcome.ReviewRequired.class);
    }

    @Test
    @DisplayName("401, 403은 createdAt + 60분이 지나도 15분 뒤 재조회, 횟수 유지")
    void retriesUnauthorizedRegardlessOfElapsedTime() {
        for (LocalDateTime now : List.of(NOW, REVIEW_AT, REVIEW_AT.plusHours(5))) {
            Outcome outcome = decide(new LookupResult.Unauthorized(), charge(1), now);

            assertThat(outcome).isEqualTo(new Outcome.RetryLater(now.plusMinutes(15), 1, true));
        }
    }

    @Test
    @DisplayName("조회 실패: 현재 0회면 15분 뒤, 횟수 +1")
    void retriesAfter15MinutesOnFirstLookupFailure() {
        assertThat(decide(new LookupResult.Failed(), charge(0), NOW))
                .isEqualTo(new Outcome.RetryLater(NOW.plusMinutes(15), 1, false));
    }

    @Test
    @DisplayName("조회 실패: 현재 1회면 60분 뒤, 횟수 +1")
    void retriesAfter60MinutesOnSecondLookupFailure() {
        assertThat(decide(new LookupResult.Failed(), charge(1), NOW))
                .isEqualTo(new Outcome.RetryLater(NOW.plusMinutes(60), 2, false));
    }

    @Test
    @DisplayName("조회 실패: 현재 2회면 REVIEW_REQUIRED, 횟수 +1")
    void reviewsOnThirdLookupFailure() {
        assertThat(decide(new LookupResult.Failed(), charge(2), NOW))
                .isInstanceOfSatisfying(Outcome.ReviewRequired.class,
                        review -> assertThat(review.attemptCount()).isEqualTo(3));
    }

    @Test
    @DisplayName("조회 실패는 createdAt + 60분이 지나도 횟수 기준으로만 판정한다")
    void lookupFailureIgnoresElapsedTime() {
        LocalDateTime late = REVIEW_AT.plusHours(1);

        assertThat(decide(new LookupResult.Failed(), charge(0), late))
                .isEqualTo(new Outcome.RetryLater(late.plusMinutes(15), 1, false));
    }

    @Test
    @DisplayName("DONE인데 orderId가 다르면 REVIEW_REQUIRED, 횟수 유지")
    void reviewsDoneWithDifferentOrderId() {
        assertReview(decide(found("DONE", "CHG-other", 10000L, APPROVED_AT), charge(1), NOW), 1);
    }

    @Test
    @DisplayName("DONE인데 금액이 다르면 REVIEW_REQUIRED")
    void reviewsDoneWithDifferentAmount() {
        assertReview(decide(found("DONE", ORDER_ID, 20000L, APPROVED_AT), charge(0), NOW), 0);
        assertReview(decide(found("DONE", ORDER_ID, null, APPROVED_AT), charge(0), NOW), 0);
    }

    @Test
    @DisplayName("DONE인데 approvedAt이 없으면 REVIEW_REQUIRED")
    void reviewsDoneWithoutApprovedAt() {
        assertReview(decide(found("DONE", ORDER_ID, 10000L, null), charge(0), NOW), 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELED", "PARTIAL_CANCELED", "WAITING_FOR_DEPOSIT", "SOMETHING_NEW"})
    @DisplayName("CANCELED, PARTIAL_CANCELED, WAITING_FOR_DEPOSIT, 알 수 없는 값이면 REVIEW_REQUIRED")
    void reviewsUnexpectedStatus(String status) {
        assertReview(decide(found(status, ORDER_ID, 10000L, APPROVED_AT), charge(1), NOW), 1);
    }

    @Test
    @DisplayName("200인데 상태가 없으면 REVIEW_REQUIRED")
    void reviewsMissingStatus() {
        assertReview(decide(found(null, ORDER_ID, 10000L, null), charge(0), NOW), 0);
    }

    @Test
    @DisplayName("어떤 조회 결과도 createdAt + 60분 경과만으로 FAIL이 되지 않는다")
    void neverFailsOnlyBecauseOfElapsedTime() {
        List<LookupResult> results = Stream.of("IN_PROGRESS", "READY", "DONE", "CANCELED", "WAITING_FOR_DEPOSIT")
                .<LookupResult>map(status -> found(status, ORDER_ID, 10000L, null))
                .toList();
        List<LookupResult> all = Stream.concat(results.stream(), Stream.of(
                new LookupResult.NotFound(), new LookupResult.Unauthorized(), new LookupResult.Failed())).toList();

        for (LookupResult result : all) {
            for (int attempts = 0; attempts <= 2; attempts++) {
                for (LocalDateTime now : List.of(REVIEW_AT, REVIEW_AT.plusDays(3))) {
                    assertThat(decide(result, charge(attempts), now)).isNotInstanceOf(Outcome.Fail.class);
                }
            }
        }
    }

    private Outcome decide(LookupResult result, ChargeRecoveryTarget charge, LocalDateTime now) {
        return decision.decide(result, charge, now);
    }

    private static void assertReview(Outcome outcome, int attemptCount) {
        assertThat(outcome).isInstanceOfSatisfying(Outcome.ReviewRequired.class,
                review -> assertThat(review.attemptCount()).isEqualTo(attemptCount));
    }

    private static LookupResult found(String status, String orderId, Long totalAmount, OffsetDateTime approvedAt) {
        return new LookupResult.Found(new TossPaymentResponse("pk", orderId, status, totalAmount, approvedAt));
    }

    private static ChargeRecoveryTarget charge(int attemptCount) {
        return new ChargeRecoveryTarget(1L, ORDER_ID, AMOUNT, CREATED_AT, attemptCount, NOW.plusMinutes(2));
    }
}

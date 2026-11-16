package com.justcommit.backend.payment.application.recovery;

import com.justcommit.backend.payment.infrastructure.pg.TossPaymentResponse;
import com.justcommit.backend.payment.infrastructure.repository.ChargeRecoveryTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

// 결과 불명 충전 판정: 토스 조회 결과로 무엇을 할지만 정한다 (DB, 토스 호출 없음)
//
// - FAILED는 토스가 ABORTED, EXPIRED를 돌려줄 때만. createdAt + reviewAfter 경과나 404는 FAILED 근거가 아니다
// - 승인을 다시 호출하지 않는다 (IN_PROGRESS여도)
// - paymentKey는 복호화하거나 비교하지 않는다. orderId로 조회했고, orderId는 충전마다 유일하며(CHG- + UUID),
//   승인은 우리 서버만 시크릿 키로 할 수 있으므로 orderId와 금액이 맞으면 우리 충전의 결제다
@Component
@RequiredArgsConstructor
public class ChargeRecoveryDecision {

    private static final String DONE = "DONE";

    private final ChargeRecoveryProperties properties;

    public Outcome decide(LookupResult result, ChargeRecoveryTarget charge, LocalDateTime now) {
        return switch (result) {
            case LookupResult.Found found -> decideByStatus(found.payment(), charge, now);
            case LookupResult.NotFound notFound -> unconfirmed(charge, now, "토스 결제 없음(404)");
            case LookupResult.Unauthorized unauthorized ->
                    // 충전 건이 아니라 키나 권한 설정 문제: 횟수를 올리지 않고 REVIEW_REQUIRED로도 보내지 않는다
                    new Outcome.RetryLater(now.plus(properties.authErrorDelay()), charge.attemptCount(), true);
            case LookupResult.Failed failed -> lookupFailed(charge, now);
        };
    }

    private Outcome decideByStatus(TossPaymentResponse payment, ChargeRecoveryTarget charge, LocalDateTime now) {
        String status = payment == null ? null : payment.status();
        if (status == null) {
            return review(charge, "토스 응답에 상태 없음");
        }
        return switch (status) {
            case DONE -> matches(payment, charge)
                    ? new Outcome.Approve(payment.approvedAt().toLocalDateTime())
                    : review(charge, "토스 DONE 정보 불일치: orderId=" + payment.orderId()
                            + ", 토스 금액=" + payment.totalAmount() + ", approvedAt=" + payment.approvedAt());
            case "ABORTED", "EXPIRED" -> new Outcome.Fail(status);
            case "IN_PROGRESS", "READY" -> unconfirmed(charge, now, "토스 상태 " + status);
            // CANCELED, PARTIAL_CANCELED, WAITING_FOR_DEPOSIT, 알 수 없는 값
            default -> review(charge, "예상 밖 토스 상태: " + status);
        };
    }

    private boolean matches(TossPaymentResponse payment, ChargeRecoveryTarget charge) {
        return charge.pgOrderNo().equals(payment.orderId())
                && payment.totalAmount() != null
                && charge.amount().compareTo(BigDecimal.valueOf(payment.totalAmount())) == 0
                && payment.approvedAt() != null;
    }

    // 아직 승인 전이거나 토스에 없음: 기준 시각 전이면 다시 조회, 이후면 사람이 확인 (횟수 변화 없음)
    private Outcome unconfirmed(ChargeRecoveryTarget charge, LocalDateTime now, String reason) {
        LocalDateTime reviewAt = charge.createdAt().plus(properties.reviewAfter());
        if (now.isBefore(reviewAt)) {
            return new Outcome.RetryLater(now.plus(properties.unconfirmedDelay()), charge.attemptCount(), false);
        }
        return review(charge, reason + ", 충전 생성 후 " + properties.reviewAfter().toMinutes() + "분 경과");
    }

    // 조회 실패만 횟수로 센다: 1회 → 15분, 2회 → 60분 뒤 다시, 3회째면 REVIEW_REQUIRED
    private Outcome lookupFailed(ChargeRecoveryTarget charge, LocalDateTime now) {
        int failures = charge.attemptCount() + 1;
        if (failures >= properties.maxLookupFailures()) {
            return new Outcome.ReviewRequired("토스 조회 " + failures + "회 실패", failures);
        }
        Duration delay = properties.retryDelayAfter(failures);
        return new Outcome.RetryLater(now.plus(delay), failures, false);
    }

    private Outcome review(ChargeRecoveryTarget charge, String reason) {
        return new Outcome.ReviewRequired(reason, charge.attemptCount());
    }

    // 토스 결제 조회 결과
    public sealed interface LookupResult {
        record Found(TossPaymentResponse payment) implements LookupResult {}
        record NotFound() implements LookupResult {}
        record Unauthorized() implements LookupResult {}
        record Failed() implements LookupResult {}
    }

    // 판정. attemptCount는 반영 뒤의 조회 실패 횟수
    public sealed interface Outcome {
        record Approve(LocalDateTime approvedAt) implements Outcome {}
        record Fail(String tossStatus) implements Outcome {}
        record RetryLater(LocalDateTime nextAttemptAt, int attemptCount, boolean authError) implements Outcome {}
        record ReviewRequired(String reason, int attemptCount) implements Outcome {}
    }
}

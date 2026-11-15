package com.justcommit.backend.payment.application.recovery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

// 결과 불명 충전(PENDING) 복구 설정. 값의 원본은 docs/reference/schedulers.md
@ConfigurationProperties("custom.payment.charge-recovery")
public record ChargeRecoveryProperties(
        // 스케줄러 실행 간격 (fixedDelay)
        @DefaultValue("5m") Duration interval,
        // 한 번 실행할 때 처리하는 최대 건수
        @DefaultValue("100") int batchSize,
        // PENDING 진입 뒤 첫 조회까지 (토스 승인 응답 타임아웃 60초보다 길게)
        @DefaultValue("5m") Duration firstDelay,
        // 조회 실패 뒤 다음 조회까지. n번째 실패 뒤에는 n번째 값
        @DefaultValue({"15m", "60m"}) List<Duration> retryDelays,
        // 조회 실패가 이 횟수가 되면 REVIEW_REQUIRED
        @DefaultValue("3") int maxLookupFailures,
        // IN_PROGRESS, READY, 404일 때 다음 조회까지 (횟수에 넣지 않음)
        @DefaultValue("15m") Duration unconfirmedDelay,
        // 충전 생성(createdAt) 뒤 이만큼 지나도 확정되지 않으면 REVIEW_REQUIRED.
        // 토스의 공식 만료 시각이 아니라 replant가 정한 수동 확인 기준이라 FAILED 근거로 쓰지 않는다
        @DefaultValue("60m") Duration reviewAfter,
        // 401, 403(키나 권한 설정 문제)일 때 다음 조회까지 (횟수에 넣지 않음)
        @DefaultValue("15m") Duration authErrorDelay,
        // 한 건을 확보한 뒤 다른 인스턴스가 다시 잡지 않게 next_attempt_at을 미루는 시간.
        // 한 건 처리의 최대 시간(토스 연결 3초 + 응답 60초 + DB 반영)보다 길게
        @DefaultValue("2m") Duration lease
) {

    public ChargeRecoveryProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException("charge-recovery.batch-size는 1 이상이어야 합니다.");
        }
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("charge-recovery.lease는 0보다 커야 합니다.");
        }
        if (maxLookupFailures < 1) {
            throw new IllegalArgumentException("charge-recovery.max-lookup-failures는 1 이상이어야 합니다.");
        }
        // 마지막 실패는 REVIEW_REQUIRED라 그 전까지의 간격만 있으면 된다
        if (retryDelays.size() < maxLookupFailures - 1) {
            throw new IllegalArgumentException(
                    "charge-recovery.retry-delays는 max-lookup-failures - 1개 이상이어야 합니다.");
        }
        retryDelays = List.copyOf(retryDelays);
    }

    // 지금까지 실패 횟수(attemptCount) 뒤의 다음 조회 간격
    public Duration retryDelayAfter(int failures) {
        return retryDelays.get(Math.min(failures, retryDelays.size()) - 1);
    }
}

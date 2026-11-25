package com.justcommit.backend.settlement.infrastructure.metrics;

import com.justcommit.backend.settlement.infrastructure.repository.SettlementReconciliationMismatchRepository;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementReconciliationRunRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 수집할 때마다 DB에서 읽는 두 Gauge: 값, 값 없음(NaN)
class SettlementReconciliationMetricsTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final SettlementReconciliationMismatchRepository mismatchRepository =
            mock(SettlementReconciliationMismatchRepository.class);
    private final SettlementReconciliationRunRepository runRepository = mock(SettlementReconciliationRunRepository.class);
    private final SettlementReconciliationMetrics metrics = new SettlementReconciliationMetrics(
            mismatchRepository, runRepository, Clock.fixed(Instant.EPOCH, SEOUL), new SimpleMeterRegistry());

    @Test
    @DisplayName("미해결 불일치 수와 마지막 성공 시각(epoch 초)을 DB에서 읽는다")
    void readsFromDb() {
        LocalDateTime finishedAt = LocalDateTime.of(2026, 11, 25, 9, 40, 5);
        when(mismatchRepository.countUnresolved()).thenReturn(3L);
        when(runRepository.findLastSuccessOrBaselineAt()).thenReturn(Optional.of(finishedAt));

        assertThat(metrics.unresolvedMismatches()).isEqualTo(3.0);
        assertThat(metrics.lastSuccessEpochSeconds()).isEqualTo(finishedAt.atZone(SEOUL).toEpochSecond());
    }

    @Test
    @DisplayName("성공 행도 기준 행(BASELINE)도 없으면 NaN (0이면 미실행 알림이 바로 울림)")
    void noSuccessNorBaseline() {
        when(runRepository.findLastSuccessOrBaselineAt()).thenReturn(Optional.empty());

        assertThat(metrics.lastSuccessEpochSeconds()).isNaN();
    }

    @Test
    @DisplayName("DB를 못 읽으면 두 Gauge 모두 NaN (0으로 알림이 거짓으로 꺼지지 않게)")
    void nanOnDbFailure() {
        when(mismatchRepository.countUnresolved()).thenThrow(new IllegalStateException("DB 연결 실패"));
        when(runRepository.findLastSuccessOrBaselineAt()).thenThrow(new IllegalStateException("DB 연결 실패"));

        assertThat(metrics.unresolvedMismatches()).isNaN();
        assertThat(metrics.lastSuccessEpochSeconds()).isNaN();
    }
}

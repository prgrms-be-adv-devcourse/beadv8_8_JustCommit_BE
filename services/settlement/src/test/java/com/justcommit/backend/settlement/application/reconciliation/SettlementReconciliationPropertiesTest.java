package com.justcommit.backend.settlement.application.reconciliation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementReconciliationPropertiesTest {

    static SettlementReconciliationProperties defaults() {
        return withBatchSize(100);
    }

    static SettlementReconciliationProperties withBatchSize(int batchSize) {
        return new SettlementReconciliationProperties(true, "0 40 9 * * *", Duration.ofDays(7),
                Duration.ofMinutes(10), batchSize);
    }

    @Test
    @DisplayName("설정이 없으면 09:40 실행, 최근 7일, 유예 10분, 배치 100개")
    void bindsDefaults() {
        SettlementReconciliationProperties properties = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("custom.settlement.reconciliation", SettlementReconciliationProperties.class);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.cron()).isEqualTo("0 40 9 * * *");
        assertThat(properties.lookback()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.unreflectedGrace()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.batchSize()).isEqualTo(100);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    @DisplayName("배치 크기는 1~100 (payment API 상한)")
    void rejectsBatchSizeOutOfRange(int batchSize) {
        assertThatThrownBy(() -> withBatchSize(batchSize)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("조회 범위와 유예 시간은 0보다 커야 한다")
    void rejectsNonPositiveDurations() {
        assertThatThrownBy(() -> new SettlementReconciliationProperties(true, "0 40 9 * * *", Duration.ZERO,
                Duration.ofMinutes(10), 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementReconciliationProperties(true, "0 40 9 * * *", Duration.ofDays(7),
                Duration.ZERO, 100)).isInstanceOf(IllegalArgumentException.class);
    }
}

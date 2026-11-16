package com.justcommit.backend.settlement.application.payout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementPayoutPropertiesTest {

    static SettlementPayoutProperties defaults() {
        return new SettlementPayoutProperties(Duration.ofSeconds(10), 50, Duration.ofSeconds(30),
                List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(1)),
                Duration.ofMinutes(10), Duration.ofHours(1));
    }

    @Test
    @DisplayName("다음 시도 간격은 실패 횟수에 따라 10초 → 30초 → 이후 1분 고정이다")
    void retryDelayAfterFailures() {
        SettlementPayoutProperties properties = defaults();

        assertThat(properties.retryDelayAfter(0)).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.retryDelayAfter(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.retryDelayAfter(2)).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.retryDelayAfter(100)).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("임대 시간이 0이거나 재시도 간격이 없으면 시작하지 않는다")
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> new SettlementPayoutProperties(Duration.ofSeconds(10), 50, Duration.ZERO,
                List.of(Duration.ofSeconds(10)), Duration.ofMinutes(10), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementPayoutProperties(Duration.ofSeconds(10), 50, Duration.ofSeconds(30),
                List.of(), Duration.ofMinutes(10), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementPayoutProperties(Duration.ofSeconds(10), 0, Duration.ofSeconds(30),
                List.of(Duration.ofSeconds(10)), Duration.ofMinutes(10), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

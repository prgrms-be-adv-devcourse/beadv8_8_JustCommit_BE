package com.justcommit.backend.settlement.application.payout;

import com.justcommit.backend.settlement.application.payout.SettlementPayoutDelayMonitor.Result;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementPayoutDelayMonitorTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant START = Instant.parse("2026-11-16T03:00:00Z");

    private final SettlementRepository repository = mock(SettlementRepository.class);
    private final MutableClock clock = new MutableClock(START);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SettlementPayoutDelayMonitor monitor =
            new SettlementPayoutDelayMonitor(repository, SettlementPayoutPropertiesTest.defaults(), clock, registry);

    @Test
    @DisplayName("지연 건수를 지표 settlement_payouts_delayed로 내보내고, 해소되면 0 (#301)")
    void exportsDelayedCountMetric() {
        when(repository.countByStatusInAndCreatedAtBefore(anyCollection(), any())).thenReturn(3L);
        when(repository.findFirstByStatusInOrderByCreatedAtAsc(anyCollection())).thenReturn(Optional.empty());
        monitor.check();
        assertThat(registry.get(SettlementPayoutDelayMonitor.METRIC).gauge().value()).isEqualTo(3.0);

        when(repository.countByStatusInAndCreatedAtBefore(anyCollection(), any())).thenReturn(0L);
        monitor.check();
        assertThat(registry.get(SettlementPayoutDelayMonitor.METRIC).gauge().value()).isZero();
    }

    @Test
    @DisplayName("10분 넘은 미지급 정산을 셀 때 기준 시각은 지금 - 10분이다")
    void countsWithThreshold() {
        monitor.check();

        verify(repository).countByStatusInAndCreatedAtBefore(eq(SettlementPayoutDelayMonitor.UNFINISHED),
                eq(LocalDateTime.ofInstant(START, ZONE).minusMinutes(10)));
    }

    @Test
    @DisplayName("지연이 없으면 아무것도 남기지 않는다")
    void noDelay() {
        when(repository.countByStatusInAndCreatedAtBefore(anyCollection(), any())).thenReturn(0L);

        assertThat(monitor.check()).isEqualTo(Result.NONE);
    }

    @Test
    @DisplayName("지연은 상태 기준으로 한 번 알리고, 계속되면 1시간마다 다시 알리고, 해소되면 한 번 알린다")
    void alertsOncePerStateAndRepeatsHourly() {
        when(repository.countByStatusInAndCreatedAtBefore(anyCollection(), any())).thenReturn(3L);
        when(repository.findFirstByStatusInOrderByCreatedAtAsc(anyCollection())).thenReturn(Optional.empty());

        assertThat(monitor.check()).isEqualTo(Result.ALERTED);
        clock.advance(Duration.ofSeconds(10));
        assertThat(monitor.check()).isEqualTo(Result.STILL_DELAYED);
        clock.advance(Duration.ofMinutes(59).plusSeconds(49));
        assertThat(monitor.check()).isEqualTo(Result.STILL_DELAYED);
        clock.advance(Duration.ofSeconds(1));
        assertThat(monitor.check()).isEqualTo(Result.ALERTED);

        when(repository.countByStatusInAndCreatedAtBefore(anyCollection(), any())).thenReturn(0L);
        assertThat(monitor.check()).isEqualTo(Result.RECOVERED);
        assertThat(monitor.check()).isEqualTo(Result.NONE);

        // 다시 지연되면 1시간을 기다리지 않고 바로 알림
        when(repository.countByStatusInAndCreatedAtBefore(anyCollection(), any())).thenReturn(1L);
        assertThat(monitor.check()).isEqualTo(Result.ALERTED);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

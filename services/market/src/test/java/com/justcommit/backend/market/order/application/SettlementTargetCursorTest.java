package com.justcommit.backend.market.order.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 정산 대상 조회 커서 (#657): 문자열로 바꿨다가 다시 읽어도 (confirmedAt, sellerOrderId)가 그대로여야 한다
class SettlementTargetCursorTest {

    @Test
    @DisplayName("마이크로초까지 있는 confirmedAt이 문자열을 거쳐도 그대로다")
    void roundTripWithMicros() {
        SettlementTargetCursor cursor = new SettlementTargetCursor(
                LocalDateTime.of(2026, 11, 25, 10, 30, 0, 123_456_000), 988593L);

        assertThat(cursor.encode()).isEqualTo("2026-11-25T10:30:00.123456_988593");
        assertThat(SettlementTargetCursor.parse(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    @DisplayName("초가 0이어도 초를 붙여 쓰고, 다시 읽으면 같다")
    void roundTripWholeMinute() {
        SettlementTargetCursor cursor = new SettlementTargetCursor(LocalDateTime.of(2026, 11, 25, 10, 30), 8L);

        assertThat(cursor.encode()).isEqualTo("2026-11-25T10:30:00_8");
        assertThat(SettlementTargetCursor.parse(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    @DisplayName("첫 페이지 커서는 (from, 0): confirmedAt이 from과 같은 행부터 나온다")
    void first() {
        LocalDateTime from = LocalDateTime.of(2026, 11, 25, 0, 0);

        assertThat(SettlementTargetCursor.first(from)).isEqualTo(new SettlementTargetCursor(from, 0L));
    }

    @Test
    @DisplayName("sellerOrderId가 1보다 작거나 형식이 틀리면 IllegalArgumentException")
    void invalid() {
        assertThatThrownBy(() -> SettlementTargetCursor.parse("2026-11-25T10:30:00_0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettlementTargetCursor.parse("2026-11-25T10:30:00"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettlementTargetCursor.parse("2026-11-25_5"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

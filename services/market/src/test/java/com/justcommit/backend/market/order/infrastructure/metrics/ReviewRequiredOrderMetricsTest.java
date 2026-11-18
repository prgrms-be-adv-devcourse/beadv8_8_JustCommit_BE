package com.justcommit.backend.market.order.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReviewRequiredOrderMetricsTest {

    private final OrdersRepository ordersRepository = mock(OrdersRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ReviewRequiredOrderMetrics metrics = new ReviewRequiredOrderMetrics(ordersRepository, registry);

    @Test
    @DisplayName("확인 필요 주문 수 게이지는 갱신 전 0, 갱신하면 DB의 REVIEW_REQUIRED 수")
    void exposesReviewRequiredCount() {
        assertThat(gauge()).isZero();

        when(ordersRepository.countByStatus(OrderStatus.REVIEW_REQUIRED)).thenReturn(3L);
        metrics.refresh();

        assertThat(gauge()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("갱신이 실패하면 예외를 내지 않고 이전 값을 유지한다")
    void keepsLastValueWhenRefreshFails() {
        when(ordersRepository.countByStatus(OrderStatus.REVIEW_REQUIRED))
                .thenReturn(2L)
                .thenThrow(new IllegalStateException("db down"));
        metrics.refresh();

        assertThatCode(metrics::refresh).doesNotThrowAnyException();
        assertThat(gauge()).isEqualTo(2.0);
    }

    private double gauge() {
        return registry.get(ReviewRequiredOrderMetrics.NAME).gauge().value();
    }
}

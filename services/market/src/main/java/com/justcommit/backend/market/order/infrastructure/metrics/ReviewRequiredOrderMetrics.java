package com.justcommit.backend.market.order.infrastructure.metrics;

import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

// 확인 필요(REVIEW_REQUIRED) 주문 수 지표. 0보다 크면 Grafana가 알린다 (docs/infra/observability.md 「알림 규칙」)
// 수집할 때마다 DB를 읽지 않도록 Saga 복구 스케줄러가 1분마다 갱신한 값을 내보낸다.
// market 파드 2개가 같은 값을 내므로 알림은 max()로 건다
@Slf4j
@Component
public class ReviewRequiredOrderMetrics {

    static final String NAME = "market.orders.review.required"; // Prometheus: market_orders_review_required

    private final OrdersRepository ordersRepository;
    private final AtomicLong count = new AtomicLong();

    public ReviewRequiredOrderMetrics(OrdersRepository ordersRepository, MeterRegistry meterRegistry) {
        this.ordersRepository = ordersRepository;
        Gauge.builder(NAME, count, AtomicLong::get)
                .description("결제 결과를 확정하지 못해 사람이 확인해야 하는 주문 수")
                .register(meterRegistry);
    }

    // 실패해도 이전 값을 유지하고 다음 주기에 다시 센다. 지표 때문에 스케줄러가 멈추지 않게 예외를 밖으로 내지 않는다
    public void refresh() {
        try {
            count.set(ordersRepository.countByStatus(OrderStatus.REVIEW_REQUIRED));
        } catch (RuntimeException e) {
            log.warn("확인 필요 주문 수 갱신 실패, 이전 값 유지", e);
        }
    }
}

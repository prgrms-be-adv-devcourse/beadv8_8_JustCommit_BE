package com.justcommit.backend.product.product.application;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.product.product.event.ProductEventSource;
import com.justcommit.backend.product.product.event.ProductRegistered;
import com.justcommit.backend.product.product.event.ProductStatusChanged;
import com.justcommit.backend.product.product.event.ProductUpdated;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * product.events.v1 발행 공통 입구 (ADR 0021): 상품 ID 목록 → DB에서 바뀐 뒤 값 읽기 → payload v2 → Outbox.
 * 업무 트랜잭션 안에서 상품을 바꾼 뒤 부른다 (Outbox와 같은 트랜잭션, 롤백되면 함께 사라짐).
 */
@Component
@RequiredArgsConstructor
public class ProductEventPublisher {

    private final ProductRepository productRepository;
    private final OutboxPublisher outboxPublisher;

    @Transactional(propagation = Propagation.MANDATORY)
    public void registered(Long productId) {
        publish(List.of(productId), ProductRegistered::from);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void updated(Long productId) {
        publish(List.of(productId), ProductUpdated::from);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void statusChanged(Long productId) {
        publish(List.of(productId), ProductStatusChanged::from);
    }

    // 주문 · 판매자 기준 일괄 변경: 잠근 상품마다 보냄. 실제로 안 바뀐 상품은 버전이 그대로라 받는 쪽이 중복으로 보고 버림
    @Transactional(propagation = Propagation.MANDATORY)
    public void statusChanged(Collection<Long> productIds) {
        publish(productIds, ProductStatusChanged::from);
    }

    private void publish(Collection<Long> productIds, Function<ProductEventSource, IntegrationEvent> toEvent) {
        if (productIds.isEmpty()) {
            return;
        }
        // 엔티티 수정(@PreUpdate로 버전 +1)을 먼저 DB에 반영한 뒤 읽는다
        productRepository.flush();
        productRepository.findEventSources(productIds)
                .forEach(source -> outboxPublisher.publish(toEvent.apply(source)));
    }
}

package com.justcommit.backend.market.order.application;

import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.cart.application.CartService;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.event.OrderExpired;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

// 결제 대기 만료를 주문마다 짧은 트랜잭션으로 처리한다 (market 파드 2개, 정책 1.4 · 11.5)
@Service
@RequiredArgsConstructor
public class OrderExpirationService {

    private final OrdersRepository ordersRepository;
    private final OutboxPublisher outboxPublisher;
    private final CartService cartService;

    @Transactional
    public void expire(Long orderId) {
        // 다른 파드가 잡고 있거나(빈 값) 그사이 결제 · 만료된 주문은 건너뛴다.
        // 잠근 뒤 결제 대기 · 10분 경과를 다시 확인한다. 결제 처리 중 · 확인 필요는 결제 결과를 모르니 만료하지 않는다 (ADR 0023)
        // 결제 시작(TX2)은 조건부 UPDATE라, 여기서 먼저 잠그면 TX2는 잠금이 풀린 뒤 조건을 다시 보고 0건이 된다
        Orders order = ordersRepository.findByIdForUpdateSkipLocked(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PAYMENT_PENDING
                || order.getCreatedAt().isAfter(LocalDateTime.now().minus(OrderPaymentTransactions.PAYMENT_WINDOW))) {
            return;
        }
        order.expire();
        ordersRepository.saveAndFlush(order);
        // 예약 해제는 product에 이벤트로 알린다. 같은 트랜잭션에 기록하고 커밋 뒤 발행 (ADR 0016)
        outboxPublisher.publish(new OrderExpired(order.getId()));
        order.getSellerOrders().stream()
                .flatMap(sellerOrder -> sellerOrder.getItems().stream())
                .forEach(item -> cartService.restoreItem(order.getBuyerId(), item.getProductId(), item.getPrice()));
    }
}

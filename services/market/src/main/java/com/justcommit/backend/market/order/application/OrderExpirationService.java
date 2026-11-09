package com.justcommit.backend.market.order.application;

import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.cart.application.CartService;
import com.justcommit.backend.market.client.product.ProductUseCase;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 결제 대기 만료를 주문마다 짧은 트랜잭션으로 처리한다 (market 파드 2개, 정책 1.4 · 11.5)
@Service
@RequiredArgsConstructor
public class OrderExpirationService {

    private final OrdersRepository ordersRepository;
    private final ProductUseCase productUseCase;
    private final CartService cartService;

    @Transactional
    public void expire(Long orderId) {
        // 다른 파드가 잡고 있거나(빈 값) 그사이 결제 · 만료된 주문은 건너뛴다.
        Orders order = ordersRepository.findByIdForUpdateSkipLocked(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PAYMENT_PENDING) {
            return;
        }
        order.expire();
        ordersRepository.saveAndFlush(order);
        productUseCase.release(order.getId()); // 이미 해제된 경우 false여도 만료 처리한다.
        order.getSellerOrders().stream()
                .flatMap(sellerOrder -> sellerOrder.getItems().stream())
                .forEach(item -> cartService.restoreItem(order.getBuyerId(), item.getProductId(), item.getPrice()));
    }
}

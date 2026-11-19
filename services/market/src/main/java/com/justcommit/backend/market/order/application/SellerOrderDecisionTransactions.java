package com.justcommit.backend.market.order.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.order.application.OrderCancelTransactions.RefundClaim;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.event.OrderItemCancelled;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.SellerOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// 판매자 승인, 거절의 짧은 트랜잭션 (#284, ADR 0013). 거절 환불 REST는 이 클래스 밖, 커밋 뒤에 한다.
// 잠금 순서는 구매자 취소와 같은 주문 → 판매자 주문이라 승인, 거절, 구매자 취소가 동시에 와도 하나씩 처리된다.
// 먼저 처리된 쪽이 수락 대기를 없애므로 늦게 온 승인, 거절은 409다
@Slf4j
@Component
public class SellerOrderDecisionTransactions {

    private final TransactionTemplate transactionTemplate;
    private final OrdersRepository ordersRepository;
    private final SellerOrderRepository sellerOrderRepository;
    private final OrderCancelTransactions orderCancelTransactions;

    public SellerOrderDecisionTransactions(PlatformTransactionManager transactionManager,
                                           OrdersRepository ordersRepository,
                                           SellerOrderRepository sellerOrderRepository,
                                           OrderCancelTransactions orderCancelTransactions) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.ordersRepository = ordersRepository;
        this.sellerOrderRepository = sellerOrderRepository;
        this.orderCancelTransactions = orderCancelTransactions;
    }

    // 승인: 남은 수락 대기 주문상품을 모두 일반으로 바꾼다
    public void accept(Long sellerId, Long sellerOrderId) {
        transactionTemplate.executeWithoutResult(status -> {
            Locked locked = lock(sellerId, sellerOrderId);
            List<OrdersItem> pending = pendingItems(locked.sellerOrder());
            pending.forEach(OrdersItem::accept);
            log.info("판매자 승인: sellerOrderId={}, items={}", sellerOrderId, pending.size());
        });
    }

    // 거절: 고른 주문상품은 판매자 거절(SELLER)로 바로 환불할 취소 중, 나머지 수락 대기는 일반(수락)으로 바꾼다.
    // 판매자 주문 전부가 취소 흐름에 들어가면 배송비도 환불한다. 커밋 뒤 부를 환불 건을 돌려준다
    public List<RefundClaim> reject(Long sellerId, Long sellerOrderId, List<Long> orderItemIds) {
        return transactionTemplate.execute(status -> {
            Locked locked = lock(sellerId, sellerOrderId);
            SellerOrder sellerOrder = locked.sellerOrder();
            Set<Long> rejectIds = new HashSet<>(orderItemIds);
            Set<Long> ownIds = new HashSet<>(sellerOrder.getItems().stream().map(OrdersItem::getId).toList());
            if (!ownIds.containsAll(rejectIds)) {
                throw new BusinessException(OrderErrorCode.INVALID_REJECT_ITEMS);
            }
            List<OrdersItem> pending = pendingItems(sellerOrder);
            // 고른 상품이 이미 구매자 취소 등으로 수락 대기가 아니면 늦게 온 거절이다
            if (!pending.stream().map(OrdersItem::getId).toList().containsAll(rejectIds)) {
                throw new BusinessException(OrderErrorCode.SELLER_ORDER_NOT_ACCEPTABLE);
            }

            List<RefundClaim> claims = orderCancelTransactions.cancelBeforeAccept(locked.order(), sellerOrder,
                    item -> rejectIds.contains(item.getId()), OrderItemCancelled.CancelReason.SELLER,
                    LocalDateTime.now());
            sellerOrder.getItems().stream()
                    .filter(item -> item.getStatus() == OrderItemStatus.ACCEPT_PENDING)
                    .forEach(OrdersItem::accept);
            log.info("판매자 거절: sellerOrderId={}, rejected={}, accepted={}", sellerOrderId, rejectIds.size(),
                    pending.size() - rejectIds.size());
            return claims;
        });
    }

    // 주문 → 판매자 주문 순으로 잠근다. 본인 판매자 주문이 아니면 없는 것으로 본다
    private Locked lock(Long sellerId, Long sellerOrderId) {
        Long orderId = sellerOrderRepository.findOrderIdById(sellerOrderId).orElseThrow(()
                -> new BusinessException(OrderErrorCode.SELLER_ORDER_NOT_FOUND));
        Orders order = ordersRepository.findLockedById(orderId).orElseThrow(()
                -> new BusinessException(OrderErrorCode.SELLER_ORDER_NOT_FOUND));
        SellerOrder sellerOrder = sellerOrderRepository.findLockedById(sellerOrderId).orElseThrow(()
                -> new BusinessException(OrderErrorCode.SELLER_ORDER_NOT_FOUND));
        if (!sellerOrder.getSellerId().equals(sellerId)) {
            throw new BusinessException(OrderErrorCode.SELLER_ORDER_NOT_FOUND);
        }
        if (order.getStatus() != OrderStatus.PAID) {
            throw new BusinessException(OrderErrorCode.SELLER_ORDER_NOT_ACCEPTABLE);
        }
        return new Locked(order, sellerOrder);
    }

    // 수락 대기 주문상품. 하나도 없으면 이미 승인, 거절, 구매자 취소로 처리된 판매자 주문이다
    private static List<OrdersItem> pendingItems(SellerOrder sellerOrder) {
        List<OrdersItem> pending = sellerOrder.getItems().stream()
                .filter(item -> item.getStatus() == OrderItemStatus.ACCEPT_PENDING)
                .toList();
        if (pending.isEmpty()) {
            throw new BusinessException(OrderErrorCode.SELLER_ORDER_NOT_ACCEPTABLE);
        }
        return pending;
    }

    private record Locked(Orders order, SellerOrder sellerOrder) {
    }
}

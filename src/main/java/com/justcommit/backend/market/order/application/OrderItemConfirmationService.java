package com.justcommit.backend.market.order.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.event.PurchaseConfirmedEvent;
import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderItemAutoConfirmedEvent;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.ShipmentResult;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.infrastructure.OrdersItemRepository;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.SellerOrderRepository;
import com.justcommit.backend.market.order.infrastructure.kafka.PurchaseConfirmedEventPublisher;
import com.justcommit.backend.market.order.infrastructure.kafka.SellerOrderEventPublisher;
import com.justcommit.backend.market.shipment.application.ShipmentService;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OrderItemConfirmationService {

    private final OrdersRepository ordersRepository;
    private final OrdersItemRepository ordersItemRepository;
    private final SellerOrderRepository sellerOrderRepository;
    private final ShipmentService shipmentService;
    private final PurchaseConfirmedEventPublisher purchaseConfirmedEventPublisher;
    private final SellerOrderEventPublisher sellerOrderEventPublisher;

    @Transactional
    public void confirm(Long memberId, Long orderItemId) {
        confirmItem(memberId, orderItemId, false);
    }

    @Transactional
    public void autoConfirmOrderItem(Long orderItemId) {
        confirmItem(null, orderItemId, true);
    }

    private void confirmItem(Long memberId, Long orderItemId, boolean automatic) {
        Long orderId = ordersItemRepository.findOrderIdByItemId(orderItemId).orElseThrow(()
                -> new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_FOUND));
        // 같은 주문의 서로 다른 판매자 주문도 직렬화하기 위해 부모 주문을 먼저 잠근다.
        Orders order = ordersRepository.findLockedById(orderId).orElseThrow(()
                -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        Long sellerOrderId = ordersItemRepository.findSellerOrderIdByItemId(orderItemId).orElseThrow(()
                -> new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_FOUND));
        // 잠금 순서는 항상 주문 → 판매자 주문으로 유지한다.
        SellerOrder sellerOrder = sellerOrderRepository.findLockedById(sellerOrderId).orElseThrow(()
                -> new BusinessException(OrderErrorCode.SELLER_ORDER_NOT_FOUND));
        OrdersItem item = ordersItemRepository.findById(orderItemId).orElseThrow(()
                -> new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_FOUND));

        if (!automatic && !order.getBuyerId().equals(memberId)) {
            throw new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_FOUND);
        }

        if (item.getStatus().equals(OrderItemStatus.CONFIRMED)) {
            return;
        } else if (!item.getStatus().equals(OrderItemStatus.NORMAL)) {
            if (automatic) {
                return; // 후보 조회 뒤 취소·환불 상태로 변경된 상품은 건너뛴다.
            }
            throw new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_CONFIRMABLE);
        }

        if (order.getStatus() != OrderStatus.PAID) {
            if (automatic) {
                return;
            }
            throw new BusinessException(OrderErrorCode.ORDER_NOT_PAID);
        }

        ShipmentResult shipmentResult = shipmentService.findBySellerOrderId(sellerOrder.getId());
        if (shipmentResult == null) {
            if (automatic) {
                return;
            }
            throw new BusinessException(OrderErrorCode.SHIPMENT_NOT_FOUND);
        }
        if (!ShipmentStatus.DELIVERED.name().equals(shipmentResult.status())) {
            if (automatic) {
                return;
            }
            throw new BusinessException(OrderErrorCode.SHIPMENT_NOT_DELIVERED);
        }
        if (automatic && (shipmentResult.deliveredAt() == null
                || shipmentResult.deliveredAt().isAfter(LocalDateTime.now().minusDays(3)))) {
            return;
        }

        item.confirm();
        checkAndRequestSettlement(sellerOrder);
        updateOrderStatus(order);
        if (automatic) {
            sellerOrderEventPublisher.publishAfterCommit(new OrderItemAutoConfirmedEvent(
                    item.getId(), order.getId(), sellerOrder.getId(), order.getBuyerId(), LocalDateTime.now()));
        }
    }

    private void checkAndRequestSettlement(SellerOrder sellerOrder) {
        if (sellerOrder.getConfirmedAt() != null) {
            return;
        }
        if (sellerOrder.getSettlementBaseAmount().compareTo(BigDecimal.ZERO) < 1) {
            //미정산대상
            return;
        }

        if (!sellerOrder.getItems().stream().anyMatch(i ->
                i.getStatus().equals(OrderItemStatus.NORMAL) || i.getStatus().equals(OrderItemStatus.REFUND_REQUESTED))) {
            sellerOrder.confirmed();
            purchaseConfirmedEventPublisher.publishAfterCommit(new PurchaseConfirmedEvent(
                    sellerOrder.getId(), sellerOrder.getSellerId(), sellerOrder.getSettlementBaseAmount(),
                    sellerOrder.getConfirmedAt()));
        }
    }

    private void updateOrderStatus(Orders orders) {
        if (orders.getSellerOrders().stream().anyMatch(s -> s.getConfirmedAt() == null && s.getSettlementBaseAmount().compareTo(BigDecimal.ZERO) > 0)) {
            return;
        }
        if (orders.getSellerOrders().stream()
                .allMatch(s -> s.getSettlementBaseAmount().compareTo(BigDecimal.ZERO) == 0)) {
            orders.cancel();
        } else {
            orders.confirm();
        }
    }
}

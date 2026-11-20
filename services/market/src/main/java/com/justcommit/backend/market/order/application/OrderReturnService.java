package com.justcommit.backend.market.order.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.ReturnReason;
import com.justcommit.backend.market.ReturnRequestedEvent;
import com.justcommit.backend.market.ShipmentQuery;
import com.justcommit.backend.market.ShipmentResult;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.ReturnRequest;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.infrastructure.OrdersItemRepository;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.ReturnRequestRepository;
import com.justcommit.backend.market.order.infrastructure.SellerOrderRepository;
import com.justcommit.backend.market.order.infrastructure.kafka.SellerOrderEventPublisher;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

// 구매자 반품 신청 (#382). 승인, 거절, 자동 승인은 #383, 반품 배송과 환불은 #384.
// 잠금 순서는 구매 확정(OrderItemConfirmationService.confirmItem)과 같다: 주문 → 판매자 주문 → 주문상품 상태 확인.
// 자동 구매 확정도 같은 주문 행을 잠그고 일반 상태를 다시 확인하므로, 둘이 겹치면 먼저 잠근 쪽만 반영된다
@Service
@RequiredArgsConstructor
public class OrderReturnService {

    private final OrdersRepository ordersRepository;
    private final SellerOrderRepository sellerOrderRepository;
    private final OrdersItemRepository ordersItemRepository;
    private final ReturnRequestRepository returnRequestRepository;
    private final ShipmentQuery shipmentQuery;
    private final SellerOrderEventPublisher sellerOrderEventPublisher;

    @Transactional
    public void request(Long memberId, Long orderItemId, ReturnReason reason, String reasonDetail) {
        Long orderId = ordersItemRepository.findOrderIdByItemId(orderItemId).orElseThrow(()
                -> new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_FOUND));
        Orders order = ordersRepository.findLockedById(orderId).orElseThrow(()
                -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        Long sellerOrderId = ordersItemRepository.findSellerOrderIdByItemId(orderItemId).orElseThrow(()
                -> new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_FOUND));
        SellerOrder sellerOrder = sellerOrderRepository.findLockedById(sellerOrderId).orElseThrow(()
                -> new BusinessException(OrderErrorCode.SELLER_ORDER_NOT_FOUND));
        OrdersItem item = ordersItemRepository.findById(orderItemId).orElseThrow(()
                -> new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_FOUND));

        // 남의 주문상품은 있는지도 알리지 않는다 (구매 확정, 취소와 같음)
        if (!order.getBuyerId().equals(memberId)) {
            throw new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_FOUND);
        }
        // 이미 신청한 상품은 일반이 아니므로 「반품 불가」보다 먼저 본다
        if (item.isInReturn()) {
            throw new BusinessException(OrderErrorCode.RETURN_ALREADY_REQUESTED);
        }
        if (order.getStatus() != OrderStatus.PAID || item.getStatus() != OrderItemStatus.NORMAL) {
            throw new BusinessException(OrderErrorCode.RETURN_NOT_REQUESTABLE);
        }
        ShipmentResult shipment = shipmentQuery.findBySellerOrderId(sellerOrder.getId());
        if (shipment == null || !ShipmentStatus.DELIVERED.name().equals(shipment.status())
                || shipment.deliveredAt() == null) {
            throw new BusinessException(OrderErrorCode.RETURN_NOT_REQUESTABLE);
        }
        LocalDateTime now = LocalDateTime.now();
        if (!OrdersItem.isWithinReturnPeriod(shipment.deliveredAt(), now)) {
            throw new BusinessException(OrderErrorCode.RETURN_PERIOD_EXPIRED);
        }

        ReturnRequest returnRequest;
        try {
            // 주문상품 상태로 막지 못한 중복(데이터가 어긋난 경우)은 order_item_id 유니크가 마지막으로 막는다
            returnRequest = returnRequestRepository.saveAndFlush(
                    new ReturnRequest(item.getId(), sellerOrder.getId(), reason, reasonDetail, now));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(OrderErrorCode.RETURN_ALREADY_REQUESTED);
        }
        item.requestReturn();

        sellerOrderEventPublisher.publishAfterCommit(new ReturnRequestedEvent(
                returnRequest.getId(), item.getId(), order.getId(), sellerOrder.getId(), order.getBuyerId(),
                sellerOrder.getSellerId(), item.getProductName(), reason, now));
    }
}

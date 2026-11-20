package com.justcommit.backend.market.order.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.ReturnApprovedEvent;
import com.justcommit.backend.market.ReturnDecidedBy;
import com.justcommit.backend.market.ReturnReason;
import com.justcommit.backend.market.ReturnRejectedEvent;
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
import com.justcommit.backend.market.shipment.application.ShipmentService;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

// 구매자 반품 신청 (#382), 판매자 승인, 거절과 48시간 자동 승인 (#383). 반품 도착과 환불은 #384.
// 잠금 순서는 구매 확정(OrderItemConfirmationService.confirmItem)과 같다: 주문 → 판매자 주문 → 주문상품 상태 확인.
// 자동 구매 확정도 같은 주문 행을 잠그고 일반 상태를 다시 확인하므로, 둘이 겹치면 먼저 잠근 쪽만 반영된다.
// 승인, 거절, 자동 승인도 같은 순서로 잠그고 반품 신청 상태를 다시 보므로 먼저 잠근 쪽만 반영된다.
// 늦은 승인, 거절은 409 RETURN_NOT_DECIDABLE, 늦은 자동 승인은 주문 행을 SKIP LOCKED로 잡아 예외 없이 건너뛴다
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderReturnService {

    private final OrdersRepository ordersRepository;
    private final SellerOrderRepository sellerOrderRepository;
    private final OrdersItemRepository ordersItemRepository;
    private final ReturnRequestRepository returnRequestRepository;
    private final ShipmentQuery shipmentQuery;
    private final SellerOrderEventPublisher sellerOrderEventPublisher;
    private final ShipmentService shipmentService;
    private final SellerOrderCompletion sellerOrderCompletion;

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

    // 판매자 승인: 반품 배송(READY)을 만들고 주문상품을 반품 배송 중으로 바꾼다. 두 번째 승인은 409라 반품 배송은 1개
    @Transactional
    public void approve(Long sellerId, Long orderItemId) {
        approveLocked(lockForSeller(sellerId, orderItemId), ReturnDecidedBy.SELLER, LocalDateTime.now());
    }

    // 판매자 거절: 주문상품을 바로 구매 확정하고 판매자 주문 종료 판정을 한다 (마지막 미완료 상품이면 정산 이벤트)
    @Transactional
    public void reject(Long sellerId, Long orderItemId) {
        Locked locked = lockForSeller(sellerId, orderItemId);
        LocalDateTime now = LocalDateTime.now();
        locked.item().rejectReturn();
        locked.returnRequest().reject(ReturnDecidedBy.SELLER, now);
        sellerOrderCompletion.complete(locked.order(), locked.sellerOrder());
        sellerOrderEventPublisher.publishAfterCommit(new ReturnRejectedEvent(locked.returnRequest().getId(),
                locked.item().getId(), locked.order().getId(), locked.sellerOrder().getId(),
                locked.order().getBuyerId(), locked.sellerOrder().getSellerId(), locked.item().getProductName(),
                ReturnDecidedBy.SELLER, now));
    }

    // 48시간 자동 승인: 건마다 짧은 트랜잭션. 주문 행은 SKIP LOCKED라 판매자 응답, 구매 확정 등이 잡고 있으면 건너뛴다.
    // 판매자 주문은 주문을 잡은 뒤 기다려서 잠근다 (판매자 주문에 SKIP LOCKED를 걸면 잠금 순서가 깨짐).
    // 잠근 뒤 반품 신청, 응답 기간 경과를 다시 보고 하나라도 아니면 먼저 처리된 쪽이 이긴 것이라 false
    @Transactional
    public boolean autoApprove(Long returnRequestId) {
        Long orderItemId = returnRequestRepository.findOrderItemIdById(returnRequestId).orElse(null);
        if (orderItemId == null) {
            return false;
        }
        Long orderId = ordersItemRepository.findOrderIdByItemId(orderItemId).orElse(null);
        if (orderId == null) {
            warnStuck(returnRequestId, "주문상품의 주문을 찾을 수 없음");
            return false;
        }
        Orders order = ordersRepository.findByIdForUpdateSkipLocked(orderId).orElse(null);
        if (order == null) {
            return false; // 다른 쪽이 주문을 잡고 있음. 다음 주기에 다시 본다
        }
        Long sellerOrderId = ordersItemRepository.findSellerOrderIdByItemId(orderItemId).orElse(null);
        SellerOrder sellerOrder = sellerOrderId == null ? null : sellerOrderRepository.findLockedById(sellerOrderId).orElse(null);
        OrdersItem item = ordersItemRepository.findById(orderItemId).orElse(null);
        ReturnRequest returnRequest = returnRequestRepository.findByOrderItemId(orderItemId).orElse(null);
        if (returnRequest == null || !returnRequest.isRequested()) {
            return false; // 그사이 판매자가 응답함 (먼저 잠근 쪽이 이김)
        }
        LocalDateTime now = LocalDateTime.now();
        // 응답 전 반품 신청인데 승인할 수 없으면 데이터가 어긋난 것이다. 이 행은 후보 앞자리에 계속 남아
        // 다음 주기에도 다시 읽히므로(뒤 후보가 밀릴 수 있음) 사람이 확인하도록 경고를 남긴다
        if (sellerOrder == null || item == null || !isDecidable(order, item, returnRequest)) {
            warnStuck(returnRequestId, "주문 " + order.getStatus() + ", 주문상품 "
                    + (item == null ? "없음" : item.getStatus()) + (sellerOrder == null ? ", 판매자 주문 없음" : ""));
            return false;
        }
        if (!returnRequest.isResponsePeriodOver(now)) {
            return false;
        }
        approveLocked(new Locked(order, sellerOrder, item, returnRequest), ReturnDecidedBy.AUTO, now);
        return true;
    }

    private static void warnStuck(Long returnRequestId, String reason) {
        log.warn("응답 전 반품 신청을 자동 승인할 수 없어 건너뜀, 확인 필요: returnRequestId={}, 이유={}",
                returnRequestId, reason);
    }

    // 반품 배송은 그 주문상품 하나만 담는다 (반품은 주문상품마다 새 배송).
    // 반품 배송비는 그 판매자 주문의 배송비(주문 때 요금표로 계산해 저장한 값)와 같다. 같은 경로를 거꾸로 오기 때문이다
    private void approveLocked(Locked locked, ReturnDecidedBy decidedBy, LocalDateTime now) {
        Long shipmentId = shipmentService.createReturnShipment(locked.sellerOrder().getId(),
                locked.item().getProductName());
        locked.item().approveReturn();
        locked.returnRequest().approve(decidedBy, now, locked.sellerOrder().getShippingFee(), shipmentId);
        sellerOrderEventPublisher.publishAfterCommit(new ReturnApprovedEvent(locked.returnRequest().getId(),
                locked.item().getId(), locked.order().getId(), locked.sellerOrder().getId(),
                locked.order().getBuyerId(), locked.sellerOrder().getSellerId(), locked.item().getProductName(),
                decidedBy, now));
    }

    // 주문 → 판매자 주문 순으로 잠그고, 잠근 뒤 반품 신청을 읽는다. 본인 판매자 주문이 아니면 없는 것으로 본다.
    // 남의 주문상품 요청이 남의 주문 행을 잠그지 않도록 잠그기 전에 판매자를 먼저 확인하고, 잠근 뒤에도 다시 확인한다
    private Locked lockForSeller(Long sellerId, Long orderItemId) {
        Long orderId = ordersItemRepository.findOrderIdByItemId(orderItemId).orElseThrow(()
                -> new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_FOUND));
        Long sellerOrderId = ordersItemRepository.findSellerOrderIdByItemId(orderItemId).orElseThrow(()
                -> new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_FOUND));
        if (!sellerOrderRepository.existsByIdAndSellerId(sellerOrderId, sellerId)) {
            throw new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_FOUND);
        }
        Orders order = ordersRepository.findLockedById(orderId).orElseThrow(()
                -> new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_FOUND));
        SellerOrder sellerOrder = sellerOrderRepository.findLockedById(sellerOrderId).orElseThrow(()
                -> new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_FOUND));
        if (!sellerOrder.getSellerId().equals(sellerId)) {
            throw new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_FOUND);
        }
        OrdersItem item = ordersItemRepository.findById(orderItemId).orElseThrow(()
                -> new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_FOUND));
        ReturnRequest returnRequest = returnRequestRepository.findByOrderItemId(orderItemId).orElse(null);
        if (returnRequest == null || !isDecidable(order, item, returnRequest)) {
            throw new BusinessException(OrderErrorCode.RETURN_NOT_DECIDABLE);
        }
        return new Locked(order, sellerOrder, item, returnRequest);
    }

    // 응답 전 반품 신청: 결제 완료 주문, 반품 신청 주문상품, 반품 신청 REQUESTED
    private static boolean isDecidable(Orders order, OrdersItem item, ReturnRequest returnRequest) {
        return order.getStatus() == OrderStatus.PAID && item.getStatus() == OrderItemStatus.RETURN_REQUESTED
                && returnRequest.isRequested();
    }

    private record Locked(Orders order, SellerOrder sellerOrder, OrdersItem item, ReturnRequest returnRequest) {
    }
}

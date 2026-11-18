package com.justcommit.backend.market.order.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.market.OrderItemCancelledEvent;
import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.SellerOrderCancelledEvent;
import com.justcommit.backend.market.ShipmentCancelResultView;
import com.justcommit.backend.market.ShipmentQuery;
import com.justcommit.backend.market.ShipmentResult;
import com.justcommit.backend.market.client.payment.RefundOutcome;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.event.OrderItemCancelled;
import com.justcommit.backend.market.order.infrastructure.OrdersItemRepository;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.SellerOrderRepository;
import com.justcommit.backend.market.order.infrastructure.kafka.SellerOrderEventPublisher;
import com.justcommit.backend.market.shipment.application.ShipmentService;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

// 구매자 취소, 환불의 짧은 트랜잭션들 (#282, ADR 0026). 환불 REST는 이 클래스 밖, 트랜잭션 사이에서 한다.
// 취소 요청은 주문상품을 취소 중으로 커밋하고 끝낸다. 판매자 주문이 전부 취소면 택배 취소 결과를 기다리고(환불 안 함),
// 일부만 취소면(배송 유지) 바로 환불한다. 환불이 확정된 뒤에만 취소 완료, 판매 재개 이벤트를 남긴다.
// 같은 클래스 안 호출로는 @Transactional이 걸리지 않으므로 TransactionTemplate으로 경계를 나눈다 (OrderPaymentTransactions와 같음)
@Slf4j
@Component
public class OrderCancelTransactions {

    // 마지막 환불 시도에서 이만큼 지나야 복구가 집는다 (결제 복구와 같은 값)
    static final Duration RECOVERY_GRACE = OrderPaymentTransactions.RECOVERY_GRACE;
    // 복구 선점 횟수 상한. 이만큼 해도 결과를 모르면 확인 필요 (첫 환불 호출은 세지 않음)
    static final int MAX_RECOVERY_ATTEMPTS = OrderPaymentTransactions.MAX_RECOVERY_ATTEMPTS;

    // 트랜잭션 밖에서 부를 환불 한 건. shippingFee가 있으면 배송비도 같은 키(sellerOrderId)로 환불한다
    // attemptCount: 이번 시도까지의 복구 선점 횟수 (첫 호출은 0)
    public record RefundClaim(Long orderItemId, Long orderId, Long sellerOrderId, Long buyerId, Long productId,
                              BigDecimal price, BigDecimal shippingFee, int attemptCount) {

        public boolean lastAttempt() {
            return attemptCount >= MAX_RECOVERY_ATTEMPTS;
        }
    }

    private final TransactionTemplate transactionTemplate;
    private final OrdersRepository ordersRepository;
    private final SellerOrderRepository sellerOrderRepository;
    private final OrdersItemRepository ordersItemRepository;
    private final ShipmentService shipmentService;
    private final ShipmentQuery shipmentQuery;
    private final OutboxPublisher outboxPublisher;
    private final SellerOrderEventPublisher sellerOrderEventPublisher;

    public OrderCancelTransactions(PlatformTransactionManager transactionManager,
                                   OrdersRepository ordersRepository,
                                   SellerOrderRepository sellerOrderRepository,
                                   OrdersItemRepository ordersItemRepository,
                                   ShipmentService shipmentService,
                                   ShipmentQuery shipmentQuery,
                                   OutboxPublisher outboxPublisher,
                                   SellerOrderEventPublisher sellerOrderEventPublisher) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.ordersRepository = ordersRepository;
        this.sellerOrderRepository = sellerOrderRepository;
        this.ordersItemRepository = ordersItemRepository;
        this.shipmentService = shipmentService;
        this.shipmentQuery = shipmentQuery;
        this.outboxPublisher = outboxPublisher;
        this.sellerOrderEventPublisher = sellerOrderEventPublisher;
    }

    // ===== 취소 요청

    // 주문 전체 취소: 판매자 주문마다 전부 취소이므로 주문상품을 취소 중 + 배송 취소 대기로 커밋하고 끝낸다 (환불 안 함)
    public void requestCancel(Long memberId, Long orderId) {
        transactionTemplate.executeWithoutResult(status -> {
            Orders order = ordersRepository.findLockedById(orderId).orElseThrow(()
                    -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
            if (!order.getBuyerId().equals(memberId)) {
                throw new BusinessException(OrderErrorCode.ORDER_NOT_FOUND);
            }
            if (order.getStatus() == OrderStatus.CANCELLED) {
                return;
            }
            if (order.getStatus() != OrderStatus.PAID) {
                throw new BusinessException(OrderErrorCode.ORDER_NOT_PAID);
            }

            List<SellerOrder> activeSellerOrders = new ArrayList<>();
            for (SellerOrder sellerOrder : sellerOrderRepository.findAllLockedByOrderId(orderId)) {
                boolean hasNormalItem = false;
                for (OrdersItem item : sellerOrder.getItems()) {
                    if (item.getStatus() == OrderItemStatus.NORMAL) {
                        hasNormalItem = true;
                    } else if (!item.isInCancellation()) {
                        throw new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_CANCELABLE);
                    }
                }
                if (hasNormalItem) {
                    activeSellerOrders.add(sellerOrder);
                }
            }
            // 모든 주문상품이 이미 취소 흐름에 있으면 같은 취소가 다시 온 것
            if (activeSellerOrders.isEmpty()) {
                return;
            }

            Map<Long, ShipmentResult> shipments = shipmentQuery.findBySellerOrderIds(
                    activeSellerOrders.stream().map(SellerOrder::getId).toList());
            for (SellerOrder sellerOrder : activeSellerOrders) {
                requireCancelableShipment(shipments.get(sellerOrder.getId()));
            }
            for (SellerOrder sellerOrder : activeSellerOrders) {
                sellerOrder.getItems().stream()
                        .filter(item -> item.getStatus() == OrderItemStatus.NORMAL)
                        .forEach(item -> item.requestCancel(null));
                sellerOrder.calculateSettlementBaseAmount();
                shipmentService.cancelShipment(sellerOrder.getId());
            }
        });
    }

    // 주문상품 하나 취소. 바로 환불할 주문상품(판매자 주문의 다른 상품이 남아 배송 유지)이면 그 환불 건을 돌려준다.
    // 판매자 주문이 전부 취소되면 택배 취소 결과를 기다리므로, 이미 취소 요청된 주문상품이면 빈 값
    public Optional<RefundClaim> requestItemCancel(Long memberId, Long orderItemId) {
        return transactionTemplate.execute(status -> {
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

            if (!order.getBuyerId().equals(memberId)) {
                throw new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_FOUND);
            }
            if (item.getStatus() == OrderItemStatus.CANCELLED || item.getStatus() == OrderItemStatus.CANCEL_PROCESSING) {
                return Optional.<RefundClaim>empty();
            }
            if (item.getStatus() != OrderItemStatus.NORMAL) {
                throw new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_CANCELABLE);
            }
            if (order.getStatus() != OrderStatus.PAID) {
                throw new BusinessException(OrderErrorCode.ORDER_NOT_PAID);
            }
            requireCancelableShipment(shipmentQuery.findBySellerOrderId(sellerOrderId));

            boolean wholeSellerOrder = sellerOrder.getItems().stream()
                    .allMatch(other -> other.getId().equals(item.getId()) || other.isInCancellation());
            if (wholeSellerOrder) {
                // 배송을 취소하므로 택배사 결과를 보고 환불한다 (ADR 0026)
                item.requestCancel(null);
                sellerOrder.calculateSettlementBaseAmount();
                shipmentService.cancelShipment(sellerOrderId);
                return Optional.<RefundClaim>empty();
            }
            item.requestCancel(LocalDateTime.now());
            sellerOrder.calculateSettlementBaseAmount();
            return Optional.of(new RefundClaim(item.getId(), orderId, sellerOrderId, order.getBuyerId(),
                    item.getProductId(), item.getPrice(), null, 0));
        });
    }

    private static void requireCancelableShipment(ShipmentResult shipment) {
        if (shipment == null) {
            throw new BusinessException(OrderErrorCode.SHIPMENT_NOT_FOUND);
        }
        if (!Objects.equals(shipment.status(), ShipmentStatus.BOOKED.name())
                && !Objects.equals(shipment.status(), ShipmentStatus.READY.name())) {
            throw new BusinessException(OrderErrorCode.SHIPMENT_NOT_CANCELABLE);
        }
    }

    // ===== Saga 복구 선점

    // 택배 취소 결과 하나를 처리한다 (SKIP LOCKED, 처리 표시까지 한 트랜잭션).
    // 취소 불가면 결과를 기다리던 주문상품을 정상으로 되돌리고 끝낸다 (환불 안 함).
    // 취소됨이면 환불 시도를 시작하고 트랜잭션 밖에서 부를 환불 건을 돌려준다
    public List<RefundClaim> claimCancelResult(Long resultId) {
        return transactionTemplate.execute(status -> {
            ShipmentCancelResultView result = shipmentQuery.lockUnprocessedCancelResult(resultId).orElse(null);
            if (result == null) {
                return List.<RefundClaim>of();
            }
            LocalDateTime now = LocalDateTime.now();
            Long sellerOrderId = result.sellerOrderId();
            Long orderId = sellerOrderRepository.findOrderIdById(sellerOrderId).orElse(null);
            if (orderId == null) {
                log.warn("택배 취소 결과의 판매자 주문이 없음, 처리 표시만 함: resultId={}, sellerOrderId={}",
                        resultId, sellerOrderId);
                shipmentQuery.markCancelResultProcessed(resultId, now);
                return List.<RefundClaim>of();
            }
            Orders order = ordersRepository.findLockedById(orderId).orElseThrow();
            SellerOrder sellerOrder = sellerOrderRepository.findLockedById(sellerOrderId).orElseThrow();
            Long buyerId = order.getBuyerId();
            BigDecimal shippingFee = sellerOrder.getShippingFee();
            boolean wholeSellerOrder = sellerOrder.getItems().stream().allMatch(OrdersItem::isInCancellation);
            List<OrdersItem> waiting = ordersItemRepository.findAwaitingCourierCancel(sellerOrderId,
                    OrderItemStatus.CANCEL_PROCESSING);

            if (!result.cancelled()) {
                int reverted = ordersItemRepository.revertAwaitingCourierCancel(sellerOrderId, now,
                        OrderItemStatus.CANCEL_PROCESSING, OrderItemStatus.NORMAL);
                // 벌크 UPDATE가 영속성 컨텍스트를 비웠으므로 다시 읽어 정산 기준액을 다시 계산한다
                sellerOrderRepository.findById(sellerOrderId).orElseThrow().calculateSettlementBaseAmount();
                shipmentQuery.markCancelResultProcessed(resultId, now);
                log.info("택배 취소 불가, 주문상품을 정상으로 되돌림: sellerOrderId={}, items={}", sellerOrderId, reverted);
                return List.<RefundClaim>of();
            }

            List<Long> itemIds = waiting.stream().map(OrdersItem::getId).toList();
            if (!itemIds.isEmpty()) {
                ordersItemRepository.startRefund(itemIds, now, OrderItemStatus.CANCEL_PROCESSING);
            }
            shipmentQuery.markCancelResultProcessed(resultId, now);
            BigDecimal feeToRefund = wholeSellerOrder && shippingFee.signum() > 0 ? shippingFee : null;
            log.info("택배 취소됨, 환불 시작: sellerOrderId={}, items={}, courierReturn={}",
                    sellerOrderId, itemIds, result.courierReturn());
            return waiting.stream()
                    .map(item -> new RefundClaim(item.getId(), orderId, sellerOrderId, buyerId, item.getProductId(),
                            item.getPrice(), feeToRefund, 0))
                    .toList();
        });
    }

    // 결과 불명 환불 하나를 선점한다 (SKIP LOCKED). 잠근 뒤 취소 중, 유예 경과를 다시 본다.
    // 복구를 이미 5번 했으면 환불을 부르지 않고 확인 필요로 바꾼다 (5번째 선점 뒤 반영 없이 유예가 지난 경우 포함)
    public Optional<RefundClaim> claimRefundRetry(Long orderItemId) {
        return transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            OrdersItem item = ordersItemRepository.findByIdForUpdateSkipLocked(orderItemId).orElse(null);
            if (item == null || item.getStatus() != OrderItemStatus.CANCEL_PROCESSING || item.getAttemptedAt() == null
                    || item.getAttemptedAt().isAfter(now.minus(RECOVERY_GRACE))) {
                return Optional.<RefundClaim>empty();
            }
            SellerOrder sellerOrder = item.getSellerOrder();
            Long orderId = sellerOrder.getOrder().getId();
            if (item.getAttemptCount() >= MAX_RECOVERY_ATTEMPTS) {
                ordersItemRepository.applyRefund(orderItemId, OrderItemStatus.REVIEW_REQUIRED, item.getLastError(), now,
                        OrderItemStatus.CANCEL_PROCESSING);
                logReviewRequired(orderItemId, orderId, item.getAttemptCount(), item.getLastError(),
                        "복구 횟수를 다 쓰고 유예가 지남");
                return Optional.<RefundClaim>empty();
            }
            RefundClaim claim = new RefundClaim(orderItemId, orderId, sellerOrder.getId(),
                    sellerOrder.getOrder().getBuyerId(), item.getProductId(), item.getPrice(),
                    shippingFeeToRetry(sellerOrder), item.getAttemptCount() + 1);
            ordersItemRepository.claimRefundRetry(orderItemId, now, OrderItemStatus.CANCEL_PROCESSING);
            return Optional.of(claim);
        });
    }

    // 판매자 주문이 전부 취소 흐름에 있고 택배가 취소됐을 때만 배송비도 다시 요청한다 (같은 키라 이미 됐으면 ALREADY_REFUNDED)
    private BigDecimal shippingFeeToRetry(SellerOrder sellerOrder) {
        if (sellerOrder.getShippingFee().signum() <= 0
                || !sellerOrder.getItems().stream().allMatch(OrdersItem::isInCancellation)) {
            return null;
        }
        ShipmentResult shipment = shipmentQuery.findBySellerOrderId(sellerOrder.getId());
        return shipment != null && ShipmentStatus.CANCELLED.name().equals(shipment.status())
                ? sellerOrder.getShippingFee() : null;
    }

    // ===== 환불 결과 반영 (취소 중일 때만, 한 번만)

    public boolean applyRefundOutcome(RefundClaim claim, RefundOutcome outcome) {
        return switch (outcome.type()) {
            case REFUNDED -> completeCancel(claim);
            case REJECTED -> requireReview(claim, outcome.reason(), "환불 거절(4xx)");
            case UNKNOWN -> claim.lastAttempt()
                    ? requireReview(claim, outcome.reason(), "마지막 복구도 결과 불명")
                    : keepProcessing(claim, outcome.reason());
        };
    }

    // 환불 확정: 취소 완료 + 판매 재개 Outbox + 판매자 알림. 주문의 모든 주문상품이 취소되면 주문도 취소
    private boolean completeCancel(RefundClaim claim) {
        Boolean applied = transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            ordersRepository.findLockedById(claim.orderId()).orElseThrow();
            sellerOrderRepository.findLockedById(claim.sellerOrderId()).orElseThrow();
            if (ordersItemRepository.applyRefund(claim.orderItemId(), OrderItemStatus.CANCELLED, null, now,
                    OrderItemStatus.CANCEL_PROCESSING) == 0) {
                return false;
            }
            // 판매 재개는 환불이 확정된 뒤에만 product에 알린다 (ADR 0016, 0026)
            outboxPublisher.publish(new OrderItemCancelled(claim.orderId(), claim.orderItemId(), claim.productId(),
                    OrderItemCancelled.CancelReason.BUYER));

            // 벌크 UPDATE가 영속성 컨텍스트를 비웠으므로 다시 읽는다
            SellerOrder sellerOrder = sellerOrderRepository.findById(claim.sellerOrderId()).orElseThrow();
            Orders order = sellerOrder.getOrder();
            List<OrdersItem> items = sellerOrder.getItems();
            if (items.stream().allMatch(item -> item.getStatus() == OrderItemStatus.CANCELLED)) {
                sellerOrderEventPublisher.publishAfterCommit(new SellerOrderCancelledEvent(
                        order.getId(), sellerOrder.getId(), sellerOrder.getSellerId(), order.getBuyerId(), now));
            } else if (items.stream().anyMatch(item -> !item.isInCancellation())) {
                // 판매자 주문의 일부만 취소 (배송 유지)
                String productName = items.stream().filter(item -> item.getId().equals(claim.orderItemId()))
                        .map(OrdersItem::getProductName).findFirst().orElse(null);
                sellerOrderEventPublisher.publishAfterCommit(new OrderItemCancelledEvent(claim.orderItemId(),
                        order.getId(), sellerOrder.getId(), sellerOrder.getSellerId(), productName, now));
            }
            boolean allCancelled = order.getSellerOrders().stream()
                    .flatMap(other -> other.getItems().stream())
                    .allMatch(item -> item.getStatus() == OrderItemStatus.CANCELLED);
            if (allCancelled && order.getStatus() == OrderStatus.PAID) {
                order.cancel();
            }
            return true;
        });
        logNotApplied(claim, Boolean.TRUE.equals(applied), OrderItemStatus.CANCELLED);
        return Boolean.TRUE.equals(applied);
    }

    private boolean requireReview(RefundClaim claim, String reason, String why) {
        Boolean applied = transactionTemplate.execute(status -> ordersItemRepository.applyRefund(claim.orderItemId(),
                OrderItemStatus.REVIEW_REQUIRED, reason, LocalDateTime.now(), OrderItemStatus.CANCEL_PROCESSING) == 1);
        if (Boolean.TRUE.equals(applied)) {
            logReviewRequired(claim.orderItemId(), claim.orderId(), claim.attemptCount(), reason, why);
        }
        logNotApplied(claim, Boolean.TRUE.equals(applied), OrderItemStatus.REVIEW_REQUIRED);
        return Boolean.TRUE.equals(applied);
    }

    // 결과 불명: 취소 중 그대로 두고 원인만 남긴다. 유예가 지나면 복구가 같은 키로 다시 요청한다
    private boolean keepProcessing(RefundClaim claim, String reason) {
        Boolean applied = transactionTemplate.execute(status -> ordersItemRepository.applyRefund(claim.orderItemId(),
                OrderItemStatus.CANCEL_PROCESSING, reason, LocalDateTime.now(), OrderItemStatus.CANCEL_PROCESSING) == 1);
        if (Boolean.TRUE.equals(applied)) {
            log.warn("환불 결과 불명, 취소 중 유지: orderItemId={}, attemptCount={}, reason={}",
                    claim.orderItemId(), claim.attemptCount(), reason);
        }
        logNotApplied(claim, Boolean.TRUE.equals(applied), OrderItemStatus.CANCEL_PROCESSING);
        return Boolean.TRUE.equals(applied);
    }

    private void logNotApplied(RefundClaim claim, boolean applied, OrderItemStatus to) {
        if (!applied) {
            // 다른 파드, 시도가 이미 반영함. 늦게 온 결과는 버린다
            log.info("환불 결과를 반영하지 않음(이미 취소 중이 아님): orderItemId={}, to={}", claim.orderItemId(), to);
        }
    }

    // 사람이 확인해야 하는 주문상품. 운영 알림, 런북의 시작점이라 ERROR 한 줄로 남긴다
    private void logReviewRequired(Long orderItemId, Long orderId, int attemptCount, String lastError, String reason) {
        log.atError()
                .setMessage("환불 결과를 확정하지 못해 확인 필요로 전환")
                .addKeyValue("orderItemId", orderItemId)
                .addKeyValue("orderId", orderId)
                .addKeyValue("attemptCount", attemptCount)
                .addKeyValue("lastError", lastError)
                .addKeyValue("reason", reason)
                .log();
    }
}

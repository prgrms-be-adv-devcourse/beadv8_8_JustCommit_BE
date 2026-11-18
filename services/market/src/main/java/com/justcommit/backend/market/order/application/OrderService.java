package com.justcommit.backend.market.order.application;

import com.justcommit.backend.common.client.exception.InternalApiResultUnknownException;
import com.justcommit.backend.common.client.exception.InternalApiUnavailableException;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.market.*;
import com.justcommit.backend.market.cart.application.CartService;
import com.justcommit.backend.market.cart.domain.CartItem;
import com.justcommit.backend.market.client.member.MemberQuery;
import com.justcommit.backend.market.client.payment.PaymentOutcome;
import com.justcommit.backend.market.order.application.OrderPaymentTransactions.AttemptSource;
import com.justcommit.backend.market.client.payment.PaymentResult;
import com.justcommit.backend.market.client.payment.PaymentStatus;
import com.justcommit.backend.market.client.payment.PaymentUseCase;
import com.justcommit.backend.market.client.product.ProductQuery;
import com.justcommit.backend.market.client.product.ProductResult;
import com.justcommit.backend.market.client.product.ProductUseCase;
import com.justcommit.backend.market.client.product.ReservationItem;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.event.OrderFailed;
import com.justcommit.backend.market.order.event.OrderItemCancelled;
import com.justcommit.backend.market.order.event.OrderPaid;
import com.justcommit.backend.market.order.infrastructure.AutoConfirmTarget;
import com.justcommit.backend.market.order.infrastructure.OrdersItemRepository;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.SellerOrderRepository;
import com.justcommit.backend.market.order.infrastructure.kafka.SellerOrderEventPublisher;
import com.justcommit.backend.market.order.presentation.CartOrderCreateRequest;
import com.justcommit.backend.market.order.presentation.OrderCreateResponse;
import com.justcommit.backend.market.shipment.application.ShipmentService;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.justcommit.backend.market.order.domain.OrderErrorCode.CHECKOUT_DATA_MISMATCH;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Duration PAYMENT_WINDOW = OrderPaymentTransactions.PAYMENT_WINDOW;

    private final OrdersRepository ordersRepository;
    private final SellerOrderRepository sellerOrderRepository;
    private final OrdersItemRepository ordersItemRepository;
    private final CartService cartService;
    private final MemberQuery memberQuery;
    private final ProductQuery productQuery;
    private final ProductUseCase productUseCase;
    private final OutboxPublisher outboxPublisher;
    private final PaymentUseCase paymentUseCase;
    private final ShipmentService shipmentService;
    private final OrderItemConfirmationService orderItemConfirmationService;
    private final OrderExpirationService orderExpirationService;
    private final ShipmentQuery shipmentQuery;
    private final SellerOrderEventPublisher sellerOrderEventPublisher;
    private final OrderPaymentTransactions orderPaymentTransactions;

    public OrderCreateResponse create(Long memberId, CartOrderCreateRequest request) {
        // 탈퇴한 회원의 주문을 막는 2차 방어 (1차는 Gateway의 토큰 폐기)
        if (memberQuery.isWithdrawn(memberId)) {
            throw new BusinessException(OrderErrorCode.WITHDRAWN_MEMBER);
        }

        List<CartItem> selectedItems = cartService.getSelectCartItems(memberId, request.cartItemIds());
        List<Long> productIds = selectedItems.stream().map(CartItem::getProductId).toList();
        List<ProductResult> products = productQuery.getProducts(productIds);
        if (products == null) {
            throw new BusinessException(CHECKOUT_DATA_MISMATCH);
        }

        Map<Long, ProductResult> productById;
        try {
            productById = products.stream().collect(Collectors.toMap(ProductResult::productId, Function.identity()));
        } catch (IllegalStateException | NullPointerException exception) {
            throw new BusinessException(CHECKOUT_DATA_MISMATCH);
        }

        Map<Long, List<ProductResult>> productsBySeller = new LinkedHashMap<>();
        for (CartItem selected : selectedItems) {
            ProductResult product = productById.get(selected.getProductId());
            if (product == null || product.sellerId() == null || product.title() == null || product.title().isBlank()
                    || product.price() == null || product.price().signum() < 0) {
                throw new BusinessException(CHECKOUT_DATA_MISMATCH);
            }
            if (!product.isPurchasable()) {
                throw new BusinessException(OrderErrorCode.PRODUCT_UNAVAILABLE);
            }
            if (product.sellerId().equals(memberId)) {
                throw new BusinessException(OrderErrorCode.OWN_PRODUCT);
            }
            if (selected.getPrice().compareTo(product.price()) != 0) {
                throw new BusinessException(OrderErrorCode.PRICE_CHANGED);
            }
            productsBySeller.computeIfAbsent(product.sellerId(), ignored -> new ArrayList<>()).add(product);
        }
        if (productById.size() != selectedItems.size()) {
            throw new BusinessException(CHECKOUT_DATA_MISMATCH);
        }

        String orderNo = generateOrderNo();
        Orders order = new Orders(orderNo, memberId, request.recipientName(), request.recipientPhone(),
                request.zipcode(), request.address1(), request.address2(), selectedItems.size());

        List<SellerOrder> sellerOrders = new ArrayList<>();
        productsBySeller.forEach((sellerId, sellerProducts) -> {
            SellerOrder sellerOrder = new SellerOrder(order, sellerId);
            sellerOrder.addItems(sellerProducts.stream()
                    .map(product -> new OrdersItem(sellerOrder, product.productId(), product.title(), product.price()))
                    .toList());
            // TODO: 배송비 정책이 정해지면 판매자별 배송비를 계산해 설정한다. 세미에서는 기본배송비 삼천원
            sellerOrder.setShippingFee(BigDecimal.valueOf(3_000));
            sellerOrders.add(sellerOrder);
        });
        order.addSellerOrders(sellerOrders);

        // TX1: 주문을 결제 대기로 먼저 커밋한다. 이후 REST는 트랜잭션 밖에서 부른다 (ADR 0023)
        Orders saved = orderPaymentTransactions.saveNewOrder(order);
        Long orderId = saved.getId();

        try {
            productUseCase.reserve(orderId, selectedItems.stream()
                    .map(item -> new ReservationItem(item.getProductId(), item.getPrice()))
                    .toList());
        } catch (BusinessException | InternalApiUnavailableException e) {
            // 409 · PRICE_CHANGED · 연결 실패: 예약이 안 된 게 확실하다. 주문만 실패로 끝내고 원래 에러로 응답한다
            orderPaymentTransactions.failBeforePayment(orderId, false);
            throw e;
        } catch (RuntimeException e) {
            // 타임아웃 · 5xx · 본문 읽기 실패: 예약은 다시 부르지 않고 주문 실패 + 예약 해제 이벤트
            log.warn("상품 예약 결과를 알 수 없음, 주문 실패: orderId={}", orderId, e);
            orderPaymentTransactions.failBeforePayment(orderId, true);
            return toResponse(orderPaymentTransactions.getOrder(orderId));
        }

        payInAttempt(orderId, saved.getBuyerId(), saved.getTotalAmount(), false);

        // 예약 실패 · 결제 확정 실패가 아니면 선택 항목을 비운다 (잔액 부족 · 결과 불명 포함, ADR 0023 장바구니)
        // 결제 결과가 아니라 DB에 반영된 상태로 판단한다. TX3가 실패해 결제 처리 중으로 남으면 복구가 결제할 수 있으므로 비우고,
        // 복구가 확정 실패로 끝나면 그때 장바구니를 되돌린다
        Orders current = orderPaymentTransactions.getOrder(orderId);
        if (current.getStatus() != OrderStatus.PAYMENT_FAILED) {
            removeCartItems(memberId, request.cartItemIds(), orderId);
        }
        return toResponse(current);
    }

    public OrderCreateResponse repayment(Long memberId, Long orderId) {
        Orders order = ordersRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        if (!order.getBuyerId().equals(memberId)) {
            throw new BusinessException(OrderErrorCode.ORDER_NOT_FOUND);
        }
        if (order.getStatus() == OrderStatus.PAID) {
            return toResponse(order);
        }
        // 재결제는 확정 실패일 때만 장바구니를 되돌린다 (create가 이미 지웠으므로). 그 밖의 결과는 장바구니를 바꾸지 않는다
        payInAttempt(orderId, order.getBuyerId(), order.getTotalAmount(), true);
        return toResponse(orderPaymentTransactions.getOrder(orderId));
    }

    // TX2 → 결제 REST → TX3. 결제 시작을 선점하지 못하면 결제를 부르지 않고 지금 상태에 맞는 에러로 끝낸다
    private PaymentOutcome payInAttempt(Long orderId, Long buyerId, BigDecimal amount, boolean restoreCartOnFailure) {
        OptionalInt attemptNo = orderPaymentTransactions.startAttempt(orderId);
        if (attemptNo.isEmpty()) {
            return rejectNotStarted(orderId);
        }
        PaymentOutcome outcome = paymentUseCase.pay(orderId, buyerId, amount);
        try {
            orderPaymentTransactions.applyOutcome(orderId, attemptNo.getAsInt(), outcome, AttemptSource.FIRST_CALL,
                    restoreCartOnFailure);
        } catch (RuntimeException e) {
            // 반영 실패(DB 오류 등): 주문은 결제 처리 중으로 남아 Saga 복구가 같은 orderId로 확정한다
            log.error("결제 결과 반영 실패, Saga 복구에 맡김: orderId={}, attemptNo={}, outcome={}",
                    orderId, attemptNo.getAsInt(), outcome.type(), e);
        }
        return outcome;
    }

    // TX2가 0건: 이미 결제됐으면 완료 응답, 기한 지난 결제 대기면 PAYMENT_WINDOW_EXPIRED, 그 밖은 결제할 수 없는 주문
    private PaymentOutcome rejectNotStarted(Long orderId) {
        Orders current = orderPaymentTransactions.getOrder(orderId);
        if (current.getStatus() == OrderStatus.PAID) {
            return PaymentOutcome.paid();
        }
        if (current.getStatus() == OrderStatus.PAYMENT_PENDING) {
            throw new BusinessException(OrderErrorCode.PAYMENT_WINDOW_EXPIRED);
        }
        throw new BusinessException(OrderErrorCode.ORDER_NOT_PAYABLE);
    }

    // 삭제 실패는 주문 상태에 영향을 주지 않는다 (이미 커밋됨)
    private void removeCartItems(Long memberId, List<Long> cartItemIds, Long orderId) {
        try {
            cartService.removeSelectedItems(memberId, cartItemIds);
        } catch (RuntimeException e) {
            log.warn("주문 후 장바구니 비우기 실패, 주문은 그대로: orderId={}", orderId, e);
        }
    }

    // 트랜잭션 없이 후보만 읽고, 주문마다 따로 커밋한다. 한 주문이 실패해도 나머지는 계속한다.
    // shortcut: 같은 주문이 매번 실패하면(DB 오류 등) 100건이 쌓일 때 뒤 주문이 밀린다. 예약 해제는 #277부터 Outbox 기록이라 외부 장애로는 실패하지 않음. 필요해지면 실패 횟수 · 제외 장치를 넣는다.
    public void expirePendingOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minus(PAYMENT_WINDOW);
        for (Orders order : ordersRepository.findTop100ByStatusAndCreatedAtBeforeOrderByIdAsc(
                OrderStatus.PAYMENT_PENDING, cutoff)) {
            try {
                orderExpirationService.expire(order.getId());
            } catch (RuntimeException exception) {
                log.error("결제 대기 만료 실패: orderId={}", order.getId(), exception);
            }
        }
    }

    // 주문 단위로 잠그므로 후보를 주문별로 묶는다. 주문상품마다 잠그면 두 파드가 같은 주문의 상품을 서로 건너뛸 수 있다.
    public void autoConfirmDeliveredItems() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(3);
        Map<Long, List<Long>> itemIdsByOrder = ordersItemRepository.findAutoConfirmTargets(
                        OrderStatus.PAID, OrderItemStatus.NORMAL, ShipmentStatus.DELIVERED, cutoff).stream()
                .collect(Collectors.groupingBy(AutoConfirmTarget::orderId, LinkedHashMap::new,
                        Collectors.mapping(AutoConfirmTarget::orderItemId, Collectors.toList())));
        itemIdsByOrder.forEach((orderId, orderItemIds) -> {
            try {
                orderItemConfirmationService.autoConfirmOrder(orderId, orderItemIds);
            } catch (RuntimeException exception) {
                log.error("자동 구매확정 실패: orderId={}, orderItemIds={}", orderId, orderItemIds, exception);
            }
        });
    }

    public void confirm(Long memberId, Long orderItemId) {
        orderItemConfirmationService.confirm(memberId, orderItemId);
    }

    @Transactional
    public void cancel(Long memberId, Long orderId) {
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

        List<SellerOrder> lockedSellerOrders = sellerOrderRepository.findAllLockedByOrderId(orderId);
        List<SellerOrder> activeSellerOrders = new ArrayList<>();
        for (SellerOrder sellerOrder : lockedSellerOrders) {
            boolean hasNormalItem = false;
            for (OrdersItem item : sellerOrder.getItems()) {
                if (item.getStatus() == OrderItemStatus.NORMAL) {
                    hasNormalItem = true;
                } else if (item.getStatus() != OrderItemStatus.CANCELLED) {
                    throw new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_CANCELABLE);
                }
            }
            if (hasNormalItem) {
                activeSellerOrders.add(sellerOrder);
            }
        }

        Map<Long, ShipmentResult> shipments = shipmentQuery.findBySellerOrderIds(
                activeSellerOrders.stream().map(SellerOrder::getId).toList());
        for (SellerOrder sellerOrder : activeSellerOrders) {
            ShipmentResult shipment = shipments.get(sellerOrder.getId());
            if (shipment == null) {
                throw new BusinessException(OrderErrorCode.SHIPMENT_NOT_FOUND);
            }
            if (!Objects.equals(shipment.status(), "BOOKED") && !Objects.equals(shipment.status(), "READY")) {
                throw new BusinessException(OrderErrorCode.SHIPMENT_NOT_CANCELABLE);
            }
        }

        List<CancelItem> cancelledItems = new ArrayList<>();
        Map<Long, BigDecimal> shippingFeesToRefund = new LinkedHashMap<>();
        for (SellerOrder sellerOrder : activeSellerOrders) {
            for (OrdersItem item : sellerOrder.getItems()) {
                if (item.getStatus() == OrderItemStatus.NORMAL) {
                    item.cancel();
                    cancelledItems.add(new CancelItem(item.getId(), item.getProductId(), item.getPrice()));
                }
            }
            sellerOrder.calculateSettlementBaseAmount();
            if (sellerOrder.getItems().stream().allMatch(item -> item.getStatus() == OrderItemStatus.CANCELLED)
                    && sellerOrder.getShippingFee().compareTo(BigDecimal.ZERO) > 0) {
                shippingFeesToRefund.put(sellerOrder.getId(), sellerOrder.getShippingFee());
            }
        }

        order.cancel();

        for (CancelItem item : cancelledItems) {
            PaymentResult result = paymentUseCase.refundCancelledItem(item.itemId(), orderId, memberId, item.price());
            if (result.status() != PaymentStatus.REFUNDED && result.status() != PaymentStatus.ALREADY_REFUNDED) {
                throw new BusinessException(CommonErrorCode.INTERNAL_ERROR);
            }
        }
        for (Map.Entry<Long, BigDecimal> shippingFee : shippingFeesToRefund.entrySet()) {
            shipmentService.cancelShipment(shippingFee.getKey());
            PaymentResult result = paymentUseCase.refundShippingFee(shippingFee.getKey(), orderId, memberId, shippingFee.getValue());
            if (result.status() != PaymentStatus.REFUNDED && result.status() != PaymentStatus.ALREADY_REFUNDED) {
                throw new BusinessException(CommonErrorCode.INTERNAL_ERROR);
            }
        }
        // 판매 재개는 상품 · 배송비 환불이 모두 성공한 뒤 주문상품마다 product에 알린다 (ADR 0016)
        for (CancelItem item : cancelledItems) {
            outboxPublisher.publish(new OrderItemCancelled(orderId, item.itemId(), item.productId(),
                    OrderItemCancelled.CancelReason.BUYER));
        }
        LocalDateTime cancelledAt = LocalDateTime.now();
        for (SellerOrder sellerOrder : activeSellerOrders) {
            sellerOrderEventPublisher.publishAfterCommit(new SellerOrderCancelledEvent(
                    orderId, sellerOrder.getId(), sellerOrder.getSellerId(), memberId, cancelledAt));
        }
    }

    @Transactional
    public void itemCancel(Long memberId, Long orderItemId) {
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

        if (item.getStatus().equals(OrderItemStatus.CANCELLED)) {
            return;
        }

        if (item.getStatus() != OrderItemStatus.NORMAL) {
            throw new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_CANCELABLE);
        }

        if (order.getStatus() != OrderStatus.PAID) {
            throw new BusinessException(OrderErrorCode.ORDER_NOT_PAID);
        }

        //배송상태 체크
        ShipmentResult shipmentResult = shipmentQuery.findBySellerOrderId(sellerOrderId);

        if (shipmentResult == null) {
            throw new BusinessException(OrderErrorCode.SHIPMENT_NOT_FOUND);
        }

        if (!(Objects.equals(shipmentResult.status(), "BOOKED") || Objects.equals(shipmentResult.status(), "READY"))) {
            throw new BusinessException(OrderErrorCode.SHIPMENT_NOT_CANCELABLE);
        }

        item.cancel();
        PaymentResult result = paymentUseCase.refundCancelledItem(item.getId(), orderId, memberId, item.getPrice());
        if (!(result.status().equals(PaymentStatus.REFUNDED) || result.status().equals(PaymentStatus.ALREADY_REFUNDED))) {
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR);
        }
        sellerOrder.calculateSettlementBaseAmount();
        boolean sellerOrderCancelled = finishCancellation(sellerOrder, order);
        // 판매 재개는 환불이 성공한 뒤 product에 알린다 (ADR 0016)
        outboxPublisher.publish(new OrderItemCancelled(orderId, item.getId(), item.getProductId(),
                OrderItemCancelled.CancelReason.BUYER));

        // 판매자 주문이 전부 취소되면 SellerOrderCancelled/ 일부만이면 OrderItemCancelled로 판매자에게 알린다 (market.events.v1)
        if (sellerOrderCancelled) {
            sellerOrderEventPublisher.publishAfterCommit(new SellerOrderCancelledEvent(
                    orderId, sellerOrderId, sellerOrder.getSellerId(), memberId, LocalDateTime.now()));
        } else {
            sellerOrderEventPublisher.publishAfterCommit(new OrderItemCancelledEvent(
                    item.getId(), orderId, sellerOrderId, sellerOrder.getSellerId(), item.getProductName(),
                    LocalDateTime.now()));
        }
    }

    private boolean finishCancellation(SellerOrder sellerOrder, Orders order) {
        boolean sellerOrderCancelled = sellerOrder.getItems().stream()
                .allMatch(item -> item.getStatus() == OrderItemStatus.CANCELLED);
        if (sellerOrderCancelled) {
            if (sellerOrder.getShippingFee().compareTo(BigDecimal.ZERO) > 0) {
                shipmentService.cancelShipment(sellerOrder.getId());
                PaymentResult result = paymentUseCase.refundShippingFee(sellerOrder.getId(), order.getId(), order.getBuyerId(), sellerOrder.getShippingFee());
                if (!(result.status().equals(PaymentStatus.REFUNDED) || result.status().equals(PaymentStatus.ALREADY_REFUNDED))) {
                    throw new BusinessException(CommonErrorCode.INTERNAL_ERROR);
                }
            }
        }
        if (order.getSellerOrders().stream()
                .flatMap(remainingSellerOrder -> remainingSellerOrder.getItems().stream())
                .allMatch(remainingItem -> remainingItem.getStatus() == OrderItemStatus.CANCELLED)) {
            order.cancel();
        }
        return sellerOrderCancelled;
    }

    // 결제 대기일 때만 결제 기한을 알려 준다
    private OrderCreateResponse toResponse(Orders order) {
        LocalDateTime expiresAt = order.getStatus() == OrderStatus.PAYMENT_PENDING
                ? order.getCreatedAt().plus(PAYMENT_WINDOW) : null;
        return new OrderCreateResponse(order.getId(), order.getOrderNo(), order.getTotalAmount(),
                order.getItemCount(), order.getCreatedAt(), order.getStatus(), expiresAt);
    }

    private record CancelItem(Long itemId, Long productId, BigDecimal price) {
    }

    private static String generateOrderNo() {
        UUID uuid = UUID.randomUUID();
        byte[] bytes = ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
        return "ORD-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

}

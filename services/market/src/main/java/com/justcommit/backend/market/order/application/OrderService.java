package com.justcommit.backend.market.order.application;

import com.justcommit.backend.common.client.exception.InternalApiResultUnknownException;
import com.justcommit.backend.common.client.exception.InternalApiUnavailableException;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import com.justcommit.backend.market.*;
import com.justcommit.backend.market.cart.application.CartService;
import com.justcommit.backend.market.cart.domain.CartItem;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.infrastructure.*;
import com.justcommit.backend.market.order.infrastructure.kafka.SellerOrderEventPublisher;
import com.justcommit.backend.market.order.presentation.CartOrderCreateRequest;
import com.justcommit.backend.market.order.presentation.OrderCreateResponse;
import com.justcommit.backend.market.shipment.application.ShipmentService;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import lombok.extern.slf4j.Slf4j;
import com.justcommit.backend.market.client.payment.PaymentResult;
import com.justcommit.backend.market.client.payment.PaymentStatus;
import com.justcommit.backend.market.client.payment.PaymentUseCase;
import com.justcommit.backend.market.client.product.ProductQuery;
import com.justcommit.backend.market.client.product.ProductResult;
import com.justcommit.backend.market.client.product.ProductUseCase;
import com.justcommit.backend.market.client.product.ReservationItem;
import lombok.RequiredArgsConstructor;
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

    private static final Duration PAYMENT_WINDOW = Duration.ofMinutes(10);

    private final OrdersRepository ordersRepository;
    private final SellerOrderRepository sellerOrderRepository;
    private final OrdersItemRepository ordersItemRepository;
    private final CartService cartService;
    private final ProductQuery productQuery;
    private final ProductUseCase productUseCase;
    private final OrderPaymentTtlStore paymentTtlStore;
    private final PaymentUseCase paymentUseCase;
    private final OrderQuery orderQuery;
    private final ShipmentService shipmentService;
    private final OrderItemConfirmationService orderItemConfirmationService;
    private final ShipmentQuery shipmentQuery;
    private final SellerOrderEventPublisher sellerOrderEventPublisher;

    @Transactional
    public OrderCreateResponse create(Long memberId, CartOrderCreateRequest request) {
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

        Orders saved = ordersRepository.saveAndFlush(order);

        // 예약을 부른 뒤로는 확정 실패만 롤백한다. 롤백하면 product에 남은 예약을 풀 주문이 없어진다 (해제 이벤트는 #277)
        try {
            productUseCase.reserve(saved.getId(), selectedItems.stream()
                    .map(item -> new ReservationItem(item.getProductId(), item.getPrice()))
                    .toList());
        } catch (BusinessException | InternalApiUnavailableException e) {
            throw e; // 409 · 연결 실패: 예약이 안 된 게 확실하다
        } catch (RuntimeException e) {
            // 타임아웃 · 5xx · 본문 읽기 실패: 예약은 다시 부르지 않고 주문을 실패로 끝낸다
            return failOrder(saved, "상품 예약 결과를 알 수 없음", e);
        }

        LocalDateTime expiresAt = null;
        boolean paid;
        try {
            paid = pay(saved, memberId);
        } catch (BusinessException | InternalApiUnavailableException e) {
            // 지갑 없음 · 연결 실패: 결제가 안 된 게 확실하다
            return failOrder(saved, "결제 실패", e);
        } catch (RuntimeException e) {
            // 5xx · 본문 읽기 실패: 결과를 단정할 수 없어 결제 대기로 두고 같은 키로 재결제한다
            log.warn("결제 응답 오류, 결제 대기 유지: orderId={}", saved.getId(), e);
            paid = false;
        }
        if (paid) {
            saved = orderPayment(saved);
        } else {
            expiresAt = saved.getCreatedAt().plus(PAYMENT_WINDOW);
            paymentTtlStore.startAfterCommit(saved.getId(), expiresAt);
        }

        cartService.removeSelectedItems(memberId, request.cartItemIds()); //이후 다시담기

        return new OrderCreateResponse(saved.getId(), saved.getOrderNo(), saved.getTotalAmount(),
                saved.getItemCount(), saved.getCreatedAt(), saved.getStatus(), expiresAt);
    }

    @Transactional
    public OrderCreateResponse repayment(Long memberId, Long orderId) {
        Orders order = ordersRepository.findLockedById(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        if (!order.getBuyerId().equals(memberId)) {
            throw new BusinessException(OrderErrorCode.ORDER_NOT_FOUND);
        }
        if (order.getStatus() == OrderStatus.PAID) {
            return toResponse(order, null);
        }
        if (order.getStatus() != OrderStatus.PAYMENT_PENDING) {
            throw new BusinessException(OrderErrorCode.ORDER_NOT_PAYABLE);
        }
        LocalDateTime expiresAt = order.getCreatedAt().plus(PAYMENT_WINDOW);
        if (!LocalDateTime.now().isBefore(expiresAt)) {
            throw new BusinessException(OrderErrorCode.PAYMENT_WINDOW_EXPIRED);
        }

        if (pay(order, memberId)) {
            order = orderPayment(order);
            paymentTtlStore.deleteAfterCommit(order.getId());
            return toResponse(order, null);
        }
        return toResponse(order, expiresAt);
    }

    @Transactional
    public void expirePendingOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minus(PAYMENT_WINDOW);
        List<Orders> pendingOrders = ordersRepository.findByStatusAndCreatedAtBefore(OrderStatus.PAYMENT_PENDING, cutoff);
        Map<Long, List<OrdersItem>> itemsByOrder = new LinkedHashMap<>();
        for (Orders order : pendingOrders) {
            itemsByOrder.put(order.getId(), order.getSellerOrders().stream()
                    .flatMap(sellerOrder -> sellerOrder.getItems().stream()).toList());
        }
        for (Orders order : pendingOrders) {
            order.expire();
            ordersRepository.saveAndFlush(order);
            productUseCase.release(order.getId()); // 이미 해제된 경우 false여도 만료 처리한다.
            itemsByOrder.get(order.getId()).stream()
                    .forEach(item -> cartService.restoreItem(order.getBuyerId(), item.getProductId(), item.getPrice()));
        }
    }

    public void autoConfirmDeliveredItems() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(3);
        for (AutoConfirmTarget target : ordersItemRepository.findAutoConfirmTargets(
                OrderStatus.PAID, OrderItemStatus.NORMAL, ShipmentStatus.DELIVERED, cutoff)) {
            try {
                orderItemConfirmationService.autoConfirmOrderItem(target.orderItemId());
            } catch (RuntimeException exception) {
                log.error("자동 구매확정 실패: orderId={}, sellerOrderId={}, orderItemId={}",
                        target.orderId(), target.sellerOrderId(), target.orderItemId(), exception);
            }
        }
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

        // releaseItem의 clearAutomatically가 영속성 컨텍스트를 비우므로 이후에는 주문 엔티티를 변경하지 않는다.
        for (CancelItem item : cancelledItems) {
            if (!productUseCase.releaseItem(item.productId())) {
                throw new BusinessException(CommonErrorCode.INTERNAL_ERROR);
            }
        }
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
        String productName = item.getProductName();
        if (!productUseCase.releaseItem(item.getProductId())) {
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR);
        }
        if (sellerOrderCancelled) {
            sellerOrderEventPublisher.publishAfterCommit(new SellerOrderCancelledEvent(
                    orderId, sellerOrderId, sellerOrder.getSellerId(), memberId, LocalDateTime.now()));
        } else {
            sellerOrderEventPublisher.publishAfterCommit(new OrderItemCancelledEvent(
                    item.getId(), orderId, sellerOrderId, sellerOrder.getSellerId(), productName, LocalDateTime.now()));
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

    // 주문을 실패로 커밋한다. 장바구니는 그대로 둔다
    private OrderCreateResponse failOrder(Orders order, String reason, RuntimeException e) {
        log.warn("{}, 주문 실패: orderId={}", reason, order.getId(), e);
        order.fail();
        return toResponse(order, null);
    }

    // 응답 타임아웃이면 결제 대기로 두고 사용자가 같은 키(orderId)로 재결제한다. 자동 복구는 #280
    private boolean pay(Orders order, Long memberId) {
        PaymentResult result;
        try {
            result = paymentUseCase.pay(order.getId(), memberId, order.getTotalAmount());
        } catch (InternalApiResultUnknownException e) {
            log.warn("결제 응답 타임아웃, 결제 대기 유지: orderId={}", order.getId(), e);
            return false;
        }
        if (result.status() == PaymentStatus.WALLET_NOT_FOUND) {
            throw new BusinessException(OrderErrorCode.WALLET_NOT_FOUND);
        }
        return result.isPaid();
    }

    private Orders orderPayment(Orders order) {
        if (!productUseCase.markSoldOut(order.getId())) {
            throw new BusinessException(OrderErrorCode.PRODUCT_UNAVAILABLE);
        }
        // 상품 벌크 업데이트가 영속성 컨텍스트를 비우므로 주문을 다시 관리 상태로 조회한다.
        Orders managed = ordersRepository.findById(order.getId())
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        managed.paid();

        OrderResult result = orderQuery.getOrder(managed.getId());
        LocalDateTime paidAt = LocalDateTime.now();
        for (SellerOrderResult sellerOrder : result.sellerOrders()) {
            sellerOrderEventPublisher.publishAfterCommit(new SellerOrderPaidEvent(
                    sellerOrder.id(), sellerOrder.sellerId(), result.buyerId(), paidAt));
        }
        shipmentService.createShipments(result);
        return managed;
    }

    private OrderCreateResponse toResponse(Orders order, LocalDateTime expiresAt) {
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

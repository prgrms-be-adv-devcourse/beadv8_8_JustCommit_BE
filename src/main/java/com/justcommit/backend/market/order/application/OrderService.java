package com.justcommit.backend.market.order.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.cart.application.CartService;
import com.justcommit.backend.market.cart.domain.CartItem;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.infrastructure.OrderPaymentTtlStore;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.presentation.CartOrderCreateRequest;
import com.justcommit.backend.market.order.presentation.OrderCreateResponse;
import com.justcommit.backend.payment.PaymentResult;
import com.justcommit.backend.payment.PaymentStatus;
import com.justcommit.backend.payment.PaymentUseCase;
import com.justcommit.backend.product.ProductQuery;
import com.justcommit.backend.product.ProductResult;
import com.justcommit.backend.product.ProductUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.justcommit.backend.market.order.domain.OrderErrorCode.CHECKOUT_DATA_MISMATCH;

@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Duration PAYMENT_WINDOW = Duration.ofMinutes(10);

    private final OrdersRepository ordersRepository;
    private final CartService cartService;
    private final ProductQuery productQuery;
    private final ProductUseCase productUseCase;
    private final OrderPaymentTtlStore paymentTtlStore;
    private final PaymentUseCase paymentUseCase;

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

        if (!productUseCase.reserve(saved.getId(), productIds)) {
            throw new BusinessException(OrderErrorCode.PRODUCT_UNAVAILABLE);
        }

        LocalDateTime expiresAt = null;
        PaymentResult paymentResult = paymentUseCase.pay(saved.getId(), memberId, saved.getTotalAmount());
        if (paymentResult.status() == PaymentStatus.WALLET_NOT_FOUND) {
            throw new BusinessException(OrderErrorCode.WALLET_NOT_FOUND);
        }
        if (paymentResult.isPaid()) {
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

        PaymentResult paymentResult = paymentUseCase.pay(orderId, memberId, order.getTotalAmount());
        if (paymentResult.status() == PaymentStatus.WALLET_NOT_FOUND) {
            throw new BusinessException(OrderErrorCode.WALLET_NOT_FOUND);
        }
        if (paymentResult.isPaid()) {
            order = orderPayment(order);
            paymentTtlStore.deleteAfterCommit(order.getId());
            return toResponse(order, null);
        }
        return toResponse(order, expiresAt);
    }

    private Orders orderPayment(Orders order) {
        if (!productUseCase.markSoldOut(order.getId())) {
            throw new BusinessException(OrderErrorCode.PRODUCT_UNAVAILABLE);
        }
        // 상품 벌크 업데이트가 영속성 컨텍스트를 비우므로 주문을 다시 관리 상태로 조회한다.
        Orders managed = ordersRepository.findById(order.getId())
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        managed.paid();
        return managed;
    }

    private OrderCreateResponse toResponse(Orders order, LocalDateTime expiresAt) {
        return new OrderCreateResponse(order.getId(), order.getOrderNo(), order.getTotalAmount(),
                order.getItemCount(), order.getCreatedAt(), order.getStatus(), expiresAt);
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

    private static String generateOrderNo() {
        UUID uuid = UUID.randomUUID();
        byte[] bytes = ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
        return "ORD-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

}

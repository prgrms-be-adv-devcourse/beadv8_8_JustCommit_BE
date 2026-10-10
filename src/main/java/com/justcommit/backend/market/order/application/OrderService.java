package com.justcommit.backend.market.order.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.cart.application.CartService;
import com.justcommit.backend.market.cart.domain.CartItem;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.OrderPaymentTtlStore;
import com.justcommit.backend.market.order.presentation.CartOrderCreateRequest;
import com.justcommit.backend.market.order.presentation.OrderCreateResponse;
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

@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Duration PAYMENT_WINDOW = Duration.ofMinutes(10);

    private final OrdersRepository ordersRepository;
    private final CartService cartService;
    private final ProductQuery productQuery;
    private final ProductUseCase productUseCase;
    private final OrderPaymentTtlStore paymentTtlStore;

    @Transactional
    public OrderCreateResponse create(Long memberId, CartOrderCreateRequest request) {
        List<CartItem> selectedItems = cartService.getSelectCartItems(memberId, request.cartItemIds());
        List<Long> productIds = selectedItems.stream().map(CartItem::getProductId).toList();
        List<ProductResult> products = productQuery.getProducts(productIds);
        if (products == null) {
            throw new BusinessException(OrderErrorCode.CHECKOUT_DATA_MISMATCH);
        }

        Map<Long, ProductResult> productById;
        try {
            productById = products.stream().collect(Collectors.toMap(ProductResult::productId, Function.identity()));
        } catch (IllegalStateException | NullPointerException exception) {
            throw new BusinessException(OrderErrorCode.CHECKOUT_DATA_MISMATCH);
        }

        Map<Long, List<ProductResult>> productsBySeller = new LinkedHashMap<>();
        for (CartItem selected : selectedItems) {
            ProductResult product = productById.get(selected.getProductId());
            if (product == null || product.sellerId() == null || product.title() == null || product.title().isBlank()
                    || product.price() == null || product.price().signum() < 0) {
                throw new BusinessException(OrderErrorCode.CHECKOUT_DATA_MISMATCH);
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
            throw new BusinessException(OrderErrorCode.CHECKOUT_DATA_MISMATCH);
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
            // TODO: 배송비 정책이 정해지면 판매자별 배송비를 계산해 설정한다.
            sellerOrder.setShippingFee(BigDecimal.ZERO);
            sellerOrders.add(sellerOrder);
        });
        order.addSellerOrders(sellerOrders);

        Orders saved = ordersRepository.saveAndFlush(order);

        boolean isReserved = productUseCase.reserve(saved.getId(), productIds);

        if (!isReserved) {
            throw new BusinessException(OrderErrorCode.PRODUCT_UNAVAILABLE);
        }

        cartService.removeSelectedItems(memberId, request.cartItemIds());
        LocalDateTime expiresAt = saved.getCreatedAt().plus(PAYMENT_WINDOW);
        paymentTtlStore.startAfterCommit(saved.getId(), expiresAt);
        // TODO: Payment 모듈 공개 API가 준비되면 잔액 확인 및 결제를 연결한다.
        // 현재는 충전 여부와 관계없이 결제대기 주문으로 예약한다.
        return new OrderCreateResponse(saved.getId(), saved.getOrderNo(), saved.getTotalAmount(),
                saved.getItemCount(), saved.getCreatedAt(), saved.getStatus(), expiresAt);
    }

    @Transactional
    public void expirePendingOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minus(PAYMENT_WINDOW);
        for (Orders order : ordersRepository.findByStatusAndCreatedAtBefore(OrderStatus.PAYMENT_PENDING, cutoff)) {
            order.expire();
            ordersRepository.saveAndFlush(order);
            productUseCase.release(order.getId()); // 이미 해제된 경우 false여도 만료 처리한다.
        }
        // TODO: 결제 API도 동일한 주문 잠금을 사용해 만료와 결제의 경합을 막는다.
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

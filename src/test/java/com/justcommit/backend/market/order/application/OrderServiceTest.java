package com.justcommit.backend.market.order.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.OrderResult;
import com.justcommit.backend.market.PurchaseConfirmedEvent;
import com.justcommit.backend.market.ShipmentResult;
import com.justcommit.backend.market.cart.application.CartService;
import com.justcommit.backend.market.cart.domain.Cart;
import com.justcommit.backend.market.cart.domain.CartItem;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.infrastructure.OrderPaymentTtlStore;
import com.justcommit.backend.market.order.infrastructure.AutoConfirmTarget;
import com.justcommit.backend.market.order.infrastructure.OrdersItemRepository;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.SellerOrderRepository;
import com.justcommit.backend.market.order.infrastructure.kafka.PurchaseConfirmedEventPublisher;
import com.justcommit.backend.market.order.presentation.CartOrderCreateRequest;
import com.justcommit.backend.market.order.presentation.OrderCreateResponse;
import com.justcommit.backend.market.shipment.application.ShipmentService;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import org.junit.jupiter.api.BeforeEach;
import com.justcommit.backend.payment.PaymentResult;
import com.justcommit.backend.payment.PaymentUseCase;
import com.justcommit.backend.product.ProductResult;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.ProductQuery;
import com.justcommit.backend.product.ProductUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock OrdersRepository ordersRepository;
    @Mock OrdersItemRepository ordersItemRepository;
    @Mock SellerOrderRepository sellerOrderRepository;
    @Mock CartService cartService;
    @Mock ProductQuery productQuery;
    @Mock ProductUseCase productUseCase;
    @Mock OrderPaymentTtlStore paymentTtlStore;
    @Mock PaymentUseCase paymentUseCase;
    @Mock OrderQuery orderQuery;
    @Mock OrderResult orderResult;
    @Mock ShipmentService shipmentService;
    @Mock PurchaseConfirmedEventPublisher purchaseConfirmedEventPublisher;
    @Mock OrderItemConfirmationService orderItemConfirmationService;
    @InjectMocks OrderService orderService;
    private OrderItemConfirmationService confirmationServiceUnderTest;
    private Orders savedOrder;

    @BeforeEach
    void setUpConfirmationService() {
        confirmationServiceUnderTest = new OrderItemConfirmationService(ordersRepository, ordersItemRepository,
                sellerOrderRepository, shipmentService, purchaseConfirmedEventPublisher);
    }

    @Test
    void stopsBeforeProductLookupWhenCartSelectionIsInvalid() {
        CartOrderCreateRequest request = request(1L);
        when(cartService.getSelectCartItems(10L, List.of(1L)))
                .thenThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        assertThatThrownBy(() -> orderService.create(10L, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));

        verify(productQuery, never()).getProducts(any());
        verify(ordersRepository, never()).saveAndFlush(any());
        verify(cartService, never()).removeSelectedItems(any(Long.class), any());
    }

    @Test
    void createsSingleItemOrderWithSnapshotsAndRemovesSelectedItem() {
        stubLookup(List.of(selected(1L, 101L, "10000")),
                List.of(product(101L, 20L, "몬스테라", "10000", true)));
        stubSavedOrder();
        stubReservationSuccess();
        stubInsufficientBalance();

        OrderCreateResponse response = orderService.create(10L, request(1L));

        ArgumentCaptor<Orders> orderCaptor = ArgumentCaptor.forClass(Orders.class);
        verify(ordersRepository).saveAndFlush(orderCaptor.capture());
        Orders order = orderCaptor.getValue();
        assertThat(order.getOrderNo()).hasSize(26).startsWith("ORD-");
        byte[] uuidBytes = Base64.getUrlDecoder().decode(order.getOrderNo().substring(4));
        assertThat(uuidBytes).hasSize(16);
        ByteBuffer uuidBuffer = ByteBuffer.wrap(uuidBytes);
        UUID uuid = new UUID(uuidBuffer.getLong(), uuidBuffer.getLong());
        assertThat(uuid.version()).isEqualTo(4);
        assertThat(uuid.variant()).isEqualTo(2);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.getBuyerId()).isEqualTo(10L);
        assertThat(order.getRecipientName()).isEqualTo("구매자");
        assertThat(order.getRecipientPhone()).isEqualTo("01012345678");
        assertThat(order.getZipcode()).isEqualTo("12345");
        assertThat(order.getItemCount()).isEqualTo(1);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("13000");
        assertThat(order.getSellerOrders()).singleElement().satisfies(sellerOrder -> {
            assertThat(sellerOrder.getSellerId()).isEqualTo(20L);
            assertThat(sellerOrder.getShippingFee()).isEqualByComparingTo("3000");
            assertThat(sellerOrder.getItems()).singleElement().satisfies(item -> {
                assertThat(item.getProductId()).isEqualTo(101L);
                assertThat(item.getProductName()).isEqualTo("몬스테라");
                assertThat(item.getPrice()).isEqualByComparingTo("10000");
            });
        });
        assertThat(response.totalAmount()).isEqualByComparingTo("13000");
        assertThat(response.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(response.expiresAt()).isEqualTo(response.createdAt().plusMinutes(10));
        verify(productQuery).getProducts(List.of(101L));
        verify(productUseCase).reserve(99L, List.of(101L));
        verify(paymentTtlStore).startAfterCommit(99L, response.expiresAt());
        verify(cartService).removeSelectedItems(10L, List.of(1L));
    }

    @Test
    void groupsMultipleItemsBySellerAndSumsProductPrices() {
        stubLookup(List.of(selected(1L, 101L, "10000"), selected(2L, 102L, "20000"),
                        selected(3L, 103L, "5000")),
                List.of(product(101L, 20L, "상품1", "10000", true),
                        product(102L, 30L, "상품2", "20000", true),
                        product(103L, 20L, "상품3", "5000", true)));
        stubSavedOrder();
        stubReservationSuccess();
        stubInsufficientBalance();

        OrderCreateResponse response = orderService.create(10L, request(1L, 2L, 3L));

        ArgumentCaptor<Orders> orderCaptor = ArgumentCaptor.forClass(Orders.class);
        verify(ordersRepository).saveAndFlush(orderCaptor.capture());
        Orders order = orderCaptor.getValue();
        assertThat(order.getSellerOrders()).hasSize(2);
        assertThat(order.getSellerOrders().getFirst().getItems()).hasSize(2);
        assertThat(order.getSellerOrders().getFirst().getTotalAmount()).isEqualByComparingTo("15000");
        assertThat(order.getSellerOrders().getLast().getItems()).hasSize(1);
        assertThat(order.getSellerOrders().getLast().getTotalAmount()).isEqualByComparingTo("20000");
        assertThat(response.totalAmount()).isEqualByComparingTo("41000");
        assertThat(response.itemCount()).isEqualTo(3);
        verify(productQuery).getProducts(List.of(101L, 102L, 103L));
        verify(cartService).removeSelectedItems(10L, List.of(1L, 2L, 3L));
    }

    @Test
    void generatesDifferentOrderNumbersForSeparateOrders() {
        stubLookup(List.of(selected(1L, 101L, "10000")),
                List.of(product(101L, 20L, "몬스테라", "10000", true)));
        stubSavedOrder();
        stubReservationSuccess();
        stubInsufficientBalance();

        OrderCreateResponse first = orderService.create(10L, request(1L));
        OrderCreateResponse second = orderService.create(10L, request(1L));

        assertThat(first.orderNo()).isNotEqualTo(second.orderNo());
    }

    @Test
    void rejectsUnavailableProductBeforeSavingOrRemovingCartItems() {
        assertRejected(OrderErrorCode.PRODUCT_UNAVAILABLE,
                product(101L, 20L, "품절", "10000", false));
    }

    @Test
    void rejectsOwnProductBeforeSavingOrRemovingCartItems() {
        assertRejected(OrderErrorCode.OWN_PRODUCT,
                product(101L, 10L, "내 상품", "10000", true));
    }

    @Test
    void rejectsChangedPriceBeforeSavingOrRemovingCartItems() {
        assertRejected(OrderErrorCode.PRICE_CHANGED,
                product(101L, 20L, "가격 변경", "12000", true));
    }

    @Test
    void rejectsMissingProductBeforeSavingOrRemovingCartItems() {
        stubLookup(List.of(selected(1L, 101L, "10000")), List.of());

        assertThatThrownBy(() -> orderService.create(10L, request(1L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.CHECKOUT_DATA_MISMATCH));
        verify(ordersRepository, never()).saveAndFlush(any());
        verify(cartService, never()).removeSelectedItems(any(Long.class), any());
    }

    @Test
    void preservesCartItemsWhenOrderSaveFails() {
        stubLookup(List.of(selected(1L, 101L, "10000")),
                List.of(product(101L, 20L, "몬스테라", "10000", true)));
        when(ordersRepository.saveAndFlush(any(Orders.class))).thenThrow(new IllegalStateException("DB write failed"));

        assertThatThrownBy(() -> orderService.create(10L, request(1L)))
                .isInstanceOf(IllegalStateException.class);

        verify(cartService, never()).removeSelectedItems(any(Long.class), any());
    }

    @Test
    void stopsCheckoutWhenProductReservationFails() {
        stubLookup(List.of(selected(1L, 101L, "10000")),
                List.of(product(101L, 20L, "몬스테라", "10000", true)));
        stubSavedOrder();

        assertThatThrownBy(() -> orderService.create(10L, request(1L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.PRODUCT_UNAVAILABLE));

        verify(productUseCase).reserve(99L, List.of(101L));
        verify(cartService, never()).removeSelectedItems(any(Long.class), any());
        verify(paymentTtlStore, never()).startAfterCommit(any(), any());
    }

    @Test
    void expiresPendingOrderAndReleasesItsProducts() {
        Orders order = new Orders("ORD-test", 10L, "구매자", "010", "12345", "서울시", null, 1);
        SellerOrder sellerOrder = new SellerOrder(order, 20L);
        sellerOrder.addItems(List.of(new OrdersItem(sellerOrder, 101L, "몬스테라", money("10000"))));
        order.addSellerOrders(List.of(sellerOrder));
        ReflectionTestUtils.setField(order, "id", 99L);
        when(ordersRepository.findByStatusAndCreatedAtBefore(eq(OrderStatus.PAYMENT_PENDING), any()))
                .thenReturn(List.of(order));
        when(productUseCase.release(99L)).thenReturn(true);

        orderService.expirePendingOrders();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        verify(ordersRepository).saveAndFlush(order);
        verify(productUseCase).release(99L);
        verify(cartService).restoreItem(10L, 101L, money("10000"));
    }

    @Test
    void paysOrderAndMarksReservedProductsSoldOut() {
        stubLookup(List.of(selected(1L, 101L, "10000")),
                List.of(product(101L, 20L, "몬스테라", "10000", true)));
        stubSavedOrder();
        stubReservationSuccess();
        when(paymentUseCase.pay(99L, 10L, money("13000"))).thenReturn(PaymentResult.paid(money("0")));
        when(productUseCase.markSoldOut(99L)).thenReturn(true);
        when(ordersRepository.findById(99L)).thenAnswer(ignored -> Optional.of(savedOrder));
        when(orderQuery.getOrder(99L)).thenReturn(orderResult);
        doAnswer(invocation -> {
            assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PAID);
            return null;
        }).when(shipmentService).createShipments(orderResult);

        OrderCreateResponse response = orderService.create(10L, request(1L));

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(response.expiresAt()).isNull();
        verify(productUseCase).markSoldOut(99L);
        verify(shipmentService).createShipments(orderResult);
        verify(paymentTtlStore, never()).startAfterCommit(any(), any());
        verify(cartService).removeSelectedItems(10L, List.of(1L));
    }

    @Test
    void doesNotCreateShipmentsWhenProductCannotBeMarkedSoldOut() {
        stubLookup(List.of(selected(1L, 101L, "10000")),
                List.of(product(101L, 20L, "몬스테라", "10000", true)));
        stubSavedOrder();
        stubReservationSuccess();
        when(paymentUseCase.pay(99L, 10L, money("13000"))).thenReturn(PaymentResult.paid(money("0")));

        assertThatThrownBy(() -> orderService.create(10L, request(1L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.PRODUCT_UNAVAILABLE));

        verify(shipmentService, never()).createShipments(any());
        verify(cartService, never()).removeSelectedItems(any(Long.class), any());
    }

    @Test
    void repaysPendingOrderBeforeExpiry() {
        Orders order = pendingOrder(10L);
        when(ordersRepository.findLockedById(99L)).thenReturn(Optional.of(order));
        when(paymentUseCase.pay(99L, 10L, money("13000"))).thenReturn(PaymentResult.paid(money("0")));
        when(productUseCase.markSoldOut(99L)).thenReturn(true);
        when(ordersRepository.findById(99L)).thenReturn(Optional.of(order));
        when(orderQuery.getOrder(99L)).thenReturn(orderResult);

        OrderCreateResponse response = orderService.repayment(10L, 99L);

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(response.expiresAt()).isNull();
        verify(productUseCase).markSoldOut(99L);
        verify(shipmentService).createShipments(orderResult);
    }

    @Test
    void rejectsRepaymentFromAnotherMember() {
        when(ordersRepository.findLockedById(99L)).thenReturn(Optional.of(pendingOrder(10L)));

        assertThatThrownBy(() -> orderService.repayment(20L, 99L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));
        verify(paymentUseCase, never()).pay(any(), any(), any());
    }

    @Test
    void rejectsRepaymentAfterPaymentWindow() {
        Orders order = pendingOrder(10L);
        ReflectionTestUtils.setField(order, "createdAt", LocalDateTime.now().minusMinutes(11));
        when(ordersRepository.findLockedById(99L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.repayment(10L, 99L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.PAYMENT_WINDOW_EXPIRED));
        verify(paymentUseCase, never()).pay(any(), any(), any());
    }

    @Test
    void keepsPendingOrderWhenRepaymentBalanceIsInsufficient() {
        Orders order = pendingOrder(10L);
        when(ordersRepository.findLockedById(99L)).thenReturn(Optional.of(order));
        when(paymentUseCase.pay(99L, 10L, money("13000")))
                .thenReturn(PaymentResult.insufficientBalance(money("1000")));

        OrderCreateResponse response = orderService.repayment(10L, 99L);

        assertThat(response.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(response.expiresAt()).isEqualTo(order.getCreatedAt().plusMinutes(10));
        verify(productUseCase, never()).markSoldOut(any());
        verify(shipmentService, never()).createShipments(any());
    }

    @Test
    void rejectsOrderCreationWhenWalletDoesNotExist() {
        stubLookup(List.of(selected(1L, 101L, "10000")),
                List.of(product(101L, 20L, "몬스테라", "10000", true)));
        stubSavedOrder();
        stubReservationSuccess();
        when(paymentUseCase.pay(99L, 10L, money("13000"))).thenReturn(PaymentResult.walletNotFound());

        assertThatThrownBy(() -> orderService.create(10L, request(1L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.WALLET_NOT_FOUND));
        verify(cartService, never()).removeSelectedItems(any(Long.class), any());
        verify(paymentTtlStore, never()).startAfterCommit(any(), any());
    }

    @Test
    void returnsPaidOrderWithoutChargingAgainOnRepeatedRequest() {
        Orders order = pendingOrder(10L);
        order.paid();
        when(ordersRepository.findLockedById(99L)).thenReturn(Optional.of(order));

        OrderCreateResponse response = orderService.repayment(10L, 99L);

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        verify(paymentUseCase, never()).pay(any(), any(), any());
        verify(productUseCase, never()).markSoldOut(any());
        verify(shipmentService, never()).createShipments(any());
    }

    @Test
    void locksSellerOrderBeforeConfirmingItem() {
        Orders order = pendingOrder(10L);
        order.paid();
        SellerOrder sellerOrder = new SellerOrder(order, 20L);
        OrdersItem item = new OrdersItem(sellerOrder, 101L, "몬스테라", money("10000"));
        sellerOrder.addItems(List.of(item));
        order.addSellerOrders(List.of(sellerOrder));
        ReflectionTestUtils.setField(sellerOrder, "id", 7L);
        ReflectionTestUtils.setField(item, "id", 11L);
        when(ordersItemRepository.findOrderIdByItemId(11L)).thenReturn(Optional.of(99L));
        when(ordersRepository.findLockedById(99L)).thenReturn(Optional.of(order));
        when(ordersItemRepository.findSellerOrderIdByItemId(11L)).thenReturn(Optional.of(7L));
        when(sellerOrderRepository.findLockedById(7L)).thenReturn(Optional.of(sellerOrder));
        when(ordersItemRepository.findById(11L)).thenReturn(Optional.of(item));
        when(shipmentService.findBySellerOrderId(7L))
                .thenReturn(new ShipmentResult(8L, "DELIVERED", LocalDateTime.now()));

        confirmationServiceUnderTest.confirm(10L, 11L);

        InOrder lookup = inOrder(ordersItemRepository, ordersRepository, sellerOrderRepository);
        lookup.verify(ordersItemRepository).findOrderIdByItemId(11L);
        lookup.verify(ordersRepository).findLockedById(99L);
        lookup.verify(ordersItemRepository).findSellerOrderIdByItemId(11L);
        lookup.verify(sellerOrderRepository).findLockedById(7L);
        lookup.verify(ordersItemRepository).findById(11L);
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
        assertThat(sellerOrder.getConfirmedAt()).isNotNull();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        ArgumentCaptor<PurchaseConfirmedEvent> event = ArgumentCaptor.forClass(PurchaseConfirmedEvent.class);
        verify(purchaseConfirmedEventPublisher).publishAfterCommit(event.capture());
        assertThat(event.getValue().sellerOrderId()).isEqualTo(7L);
        assertThat(event.getValue().sellerId()).isEqualTo(20L);
        assertThat(event.getValue().saleAmount()).isEqualByComparingTo("10000");
        assertThat(event.getValue().confirmedAt()).isEqualTo(sellerOrder.getConfirmedAt());
    }

    @Test
    void waitsForAllSellerOrderItemsBeforeMarkingSettlementReady() {
        Orders order = pendingOrder(10L);
        order.paid();
        SellerOrder sellerOrder = new SellerOrder(order, 20L);
        OrdersItem first = new OrdersItem(sellerOrder, 101L, "몬스테라", money("10000"));
        OrdersItem second = new OrdersItem(sellerOrder, 102L, "선인장", money("20000"));
        sellerOrder.addItems(List.of(first, second));
        order.addSellerOrders(List.of(sellerOrder));
        ReflectionTestUtils.setField(sellerOrder, "id", 7L);
        ReflectionTestUtils.setField(first, "id", 11L);
        ReflectionTestUtils.setField(second, "id", 12L);
        when(ordersItemRepository.findOrderIdByItemId(11L)).thenReturn(Optional.of(99L));
        when(ordersItemRepository.findOrderIdByItemId(12L)).thenReturn(Optional.of(99L));
        when(ordersRepository.findLockedById(99L)).thenReturn(Optional.of(order));
        when(ordersItemRepository.findSellerOrderIdByItemId(11L)).thenReturn(Optional.of(7L));
        when(ordersItemRepository.findSellerOrderIdByItemId(12L)).thenReturn(Optional.of(7L));
        when(sellerOrderRepository.findLockedById(7L)).thenReturn(Optional.of(sellerOrder));
        when(ordersItemRepository.findById(11L)).thenReturn(Optional.of(first));
        when(ordersItemRepository.findById(12L)).thenReturn(Optional.of(second));
        when(shipmentService.findBySellerOrderId(7L))
                .thenReturn(new ShipmentResult(8L, "DELIVERED", LocalDateTime.now()));

        confirmationServiceUnderTest.confirm(10L, 11L);
        assertThat(sellerOrder.getConfirmedAt()).isNull();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(purchaseConfirmedEventPublisher, never()).publishAfterCommit(any());

        confirmationServiceUnderTest.confirm(10L, 12L);
        assertThat(sellerOrder.getConfirmedAt()).isNotNull();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        verify(purchaseConfirmedEventPublisher).publishAfterCommit(any(PurchaseConfirmedEvent.class));
    }

    @Test
    void rejectsConfirmationWhenShipmentIsMissing() {
        OrdersItem item = stubConfirmableItem();

        assertThatThrownBy(() -> confirmationServiceUnderTest.confirm(10L, item.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.SHIPMENT_NOT_FOUND));
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.NORMAL);
        verify(purchaseConfirmedEventPublisher, never()).publishAfterCommit(any());
    }

    @Test
    void rejectsConfirmationBeforeDelivery() {
        OrdersItem item = stubConfirmableItem();
        when(shipmentService.findBySellerOrderId(7L))
                .thenReturn(new ShipmentResult(8L, "BOOKED", null));

        assertThatThrownBy(() -> confirmationServiceUnderTest.confirm(10L, item.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.SHIPMENT_NOT_DELIVERED));
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.NORMAL);
        verify(purchaseConfirmedEventPublisher, never()).publishAfterCommit(any());
    }

    @Test
    void rejectsConfirmationForAnotherBuyer() {
        OrdersItem item = stubConfirmableItem();

        assertThatThrownBy(() -> confirmationServiceUnderTest.confirm(20L, item.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_ITEM_NOT_FOUND));
        verify(shipmentService, never()).findBySellerOrderId(any());
    }

    @Test
    void rejectsConfirmationWhenOrderIsNotPaid() {
        OrdersItem item = stubConfirmableItem();
        ReflectionTestUtils.setField(item.getSellerOrder().getOrder(), "status", OrderStatus.PAYMENT_PENDING);

        assertThatThrownBy(() -> confirmationServiceUnderTest.confirm(10L, item.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_PAID));
        verify(shipmentService, never()).findBySellerOrderId(any());
    }

    @Test
    void repeatedItemConfirmationIsIdempotentEvenAfterOrderCompletes() {
        OrdersItem item = stubConfirmableItem();
        when(shipmentService.findBySellerOrderId(7L))
                .thenReturn(new ShipmentResult(8L, "DELIVERED", LocalDateTime.now()));

        confirmationServiceUnderTest.confirm(10L, item.getId());
        confirmationServiceUnderTest.confirm(10L, item.getId());

        assertThat(item.getSellerOrder().getOrder().getStatus()).isEqualTo(OrderStatus.COMPLETED);
        verify(shipmentService).findBySellerOrderId(7L);
        verify(purchaseConfirmedEventPublisher).publishAfterCommit(any(PurchaseConfirmedEvent.class));
    }

    @Test
    void autoConfirmationSkipsRefundRequestedItemAfterCandidateLookup() {
        OrdersItem item = stubConfirmableItem();
        ReflectionTestUtils.setField(item, "status", OrderItemStatus.REFUND_REQUESTED);

        confirmationServiceUnderTest.autoConfirmOrderItem(item.getId());

        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.REFUND_REQUESTED);
        verify(shipmentService, never()).findBySellerOrderId(any());
        verify(purchaseConfirmedEventPublisher, never()).publishAfterCommit(any());
    }

    @Test
    void autoConfirmationSkipsOrderThatIsNoLongerPaid() {
        OrdersItem item = stubConfirmableItem();
        ReflectionTestUtils.setField(item.getSellerOrder().getOrder(), "status", OrderStatus.CANCELLED);

        confirmationServiceUnderTest.autoConfirmOrderItem(item.getId());

        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.NORMAL);
        verify(shipmentService, never()).findBySellerOrderId(any());
    }

    @Test
    void autoConfirmationSkipsDeliveryWithoutCompletionTime() {
        OrdersItem item = stubConfirmableItem();
        when(shipmentService.findBySellerOrderId(7L))
                .thenReturn(new ShipmentResult(8L, "DELIVERED", null));

        confirmationServiceUnderTest.autoConfirmOrderItem(item.getId());

        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.NORMAL);
        verify(purchaseConfirmedEventPublisher, never()).publishAfterCommit(any());
    }

    @Test
    void doesNotPublishSettlementEventForZeroSaleAmount() {
        OrdersItem item = stubConfirmableItem();
        ReflectionTestUtils.setField(item.getSellerOrder(), "settlementBaseAmount", BigDecimal.ZERO);
        when(shipmentService.findBySellerOrderId(7L))
                .thenReturn(new ShipmentResult(8L, "DELIVERED", LocalDateTime.now()));

        confirmationServiceUnderTest.confirm(10L, item.getId());

        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
        verify(purchaseConfirmedEventPublisher, never()).publishAfterCommit(any());
    }

    @Test
    void delegatesManualConfirmationToTransactionalService() {
        orderService.confirm(10L, 11L);

        verify(orderItemConfirmationService).confirm(10L, 11L);
    }

    @Test
    void autoConfirmationContinuesWithNextItemAfterFailure() {
        when(ordersItemRepository.findAutoConfirmTargets(eq(OrderStatus.PAID), eq(OrderItemStatus.NORMAL),
                eq(ShipmentStatus.DELIVERED), any(LocalDateTime.class)))
                .thenReturn(List.of(new AutoConfirmTarget(1L, 10L, 100L),
                        new AutoConfirmTarget(1L, 10L, 101L)));
        doThrow(new IllegalStateException("일시적 실패"))
                .when(orderItemConfirmationService).autoConfirmOrderItem(100L);

        orderService.autoConfirmDeliveredItems();

        verify(orderItemConfirmationService).autoConfirmOrderItem(100L);
        verify(orderItemConfirmationService).autoConfirmOrderItem(101L);
    }

    private OrdersItem stubConfirmableItem() {
        Orders order = pendingOrder(10L);
        order.paid();
        SellerOrder sellerOrder = new SellerOrder(order, 20L);
        OrdersItem item = new OrdersItem(sellerOrder, 101L, "몬스테라", money("10000"));
        sellerOrder.addItems(List.of(item));
        order.addSellerOrders(List.of(sellerOrder));
        ReflectionTestUtils.setField(sellerOrder, "id", 7L);
        ReflectionTestUtils.setField(item, "id", 11L);
        when(ordersItemRepository.findOrderIdByItemId(11L)).thenReturn(Optional.of(99L));
        when(ordersRepository.findLockedById(99L)).thenReturn(Optional.of(order));
        when(ordersItemRepository.findSellerOrderIdByItemId(11L)).thenReturn(Optional.of(7L));
        when(sellerOrderRepository.findLockedById(7L)).thenReturn(Optional.of(sellerOrder));
        when(ordersItemRepository.findById(11L)).thenReturn(Optional.of(item));
        return item;
    }

    private void stubSavedOrder() {
        when(ordersRepository.saveAndFlush(any(Orders.class))).thenAnswer(invocation -> {
            Orders order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 99L);
            ReflectionTestUtils.setField(order, "createdAt", LocalDateTime.of(2026, 10, 10, 12, 0));
            savedOrder = order;
            return order;
        });
    }

    private void stubReservationSuccess() {
        when(productUseCase.reserve(eq(99L), anyList())).thenReturn(true);
    }

    private void stubInsufficientBalance() {
        when(paymentUseCase.pay(eq(99L), eq(10L), any(BigDecimal.class)))
                .thenReturn(PaymentResult.insufficientBalance(money("0")));
    }

    private Orders pendingOrder(long buyerId) {
        Orders order = new Orders("ORD-test", buyerId, "구매자", "010", "12345", "서울시", null, 1);
        ReflectionTestUtils.setField(order, "id", 99L);
        ReflectionTestUtils.setField(order, "totalAmount", money("13000"));
        ReflectionTestUtils.setField(order, "createdAt", LocalDateTime.now().minusMinutes(1));
        return order;
    }

    private void assertRejected(OrderErrorCode expected, ProductResult product) {
        stubLookup(List.of(selected(1L, 101L, "10000")), List.of(product));

        assertThatThrownBy(() -> orderService.create(10L, request(1L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
        verify(ordersRepository, never()).saveAndFlush(any());
        verify(cartService, never()).removeSelectedItems(any(Long.class), any());
    }

    private static CartOrderCreateRequest request(Long... cartItemIds) {
        return new CartOrderCreateRequest(List.of(cartItemIds), "구매자", "01012345678", "12345", "서울시", "101호");
    }

    private void stubLookup(List<CartItem> selectedItems, List<ProductResult> products) {
        List<Long> cartItemIds = selectedItems.stream().map(CartItem::getId).toList();
        List<Long> productIds = selectedItems.stream().map(CartItem::getProductId).toList();
        when(cartService.getSelectCartItems(10L, cartItemIds)).thenReturn(selectedItems);
        when(productQuery.getProducts(productIds)).thenReturn(products);
    }

    private static CartItem selected(long id, long productId, String price) {
        CartItem item = new CartItem(new Cart(10L), productId, money(price));
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    private static ProductResult product(long id, long sellerId, String name, String price, boolean purchasable) {
        return new ProductResult(id, sellerId, name, money(price),
                purchasable ? ProductStatus.ACTIVE : ProductStatus.SOLD_OUT, false, null);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}

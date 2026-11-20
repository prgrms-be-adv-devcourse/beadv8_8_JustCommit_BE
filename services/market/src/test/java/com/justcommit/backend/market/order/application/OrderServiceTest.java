package com.justcommit.backend.market.order.application;

import com.justcommit.backend.market.shipment.application.ShippingFeeTableService;
import com.justcommit.backend.market.client.member.MemberQuery;
import com.justcommit.backend.common.client.exception.InternalApiErrorException;
import com.justcommit.backend.common.client.exception.InternalApiResultUnknownException;
import com.justcommit.backend.common.client.exception.InternalApiUnavailableException;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderItemAutoConfirmedEvent;
import com.justcommit.backend.market.OrderItemCancelledEvent;
import com.justcommit.backend.market.SellerOrderCancelledEvent;
import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.OrderResult;
import com.justcommit.backend.market.PurchaseConfirmedEvent;
import com.justcommit.backend.market.ShipmentQuery;
import com.justcommit.backend.market.ShipmentResult;
import com.justcommit.backend.market.cart.application.CartService;
import com.justcommit.backend.market.cart.domain.Cart;
import com.justcommit.backend.market.cart.domain.CartItem;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.event.OrderExpired;
import com.justcommit.backend.market.order.event.OrderFailed;
import com.justcommit.backend.market.order.event.OrderItemCancelled;
import com.justcommit.backend.market.order.event.OrderPaid;
import com.justcommit.backend.market.order.infrastructure.AutoConfirmTarget;
import com.justcommit.backend.market.order.infrastructure.OrdersItemRepository;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.SellerOrderRepository;
import com.justcommit.backend.market.order.infrastructure.kafka.PurchaseConfirmedEventPublisher;
import com.justcommit.backend.market.order.infrastructure.kafka.SellerOrderEventPublisher;
import com.justcommit.backend.market.order.presentation.CartOrderCreateRequest;
import com.justcommit.backend.market.order.presentation.OrderCreateResponse;
import com.justcommit.backend.market.shipment.application.ShipmentService;
import com.justcommit.backend.market.shipment.domain.ShipmentDirection;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import org.junit.jupiter.api.BeforeEach;
import com.justcommit.backend.market.client.payment.PaymentOutcome;
import com.justcommit.backend.market.client.payment.PaymentResult;
import com.justcommit.backend.market.client.payment.PaymentStatus;
import com.justcommit.backend.market.client.payment.PaymentUseCase;
import com.justcommit.backend.market.client.product.ProductResult;
import com.justcommit.backend.market.client.product.ProductStatus;
import com.justcommit.backend.market.client.product.ProductQuery;
import com.justcommit.backend.market.client.product.ProductUseCase;
import com.justcommit.backend.market.client.product.ReservationItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock OrdersRepository ordersRepository;
    @Mock OrdersItemRepository ordersItemRepository;
    @Mock SellerOrderRepository sellerOrderRepository;
    @Mock CartService cartService;
    @Mock MemberQuery memberQuery;
    @Mock ProductQuery productQuery;
    @Mock ProductUseCase productUseCase;
    @Mock OutboxPublisher outboxPublisher;
    @Mock OrderExpirationService orderExpirationService;
    @Mock PaymentUseCase paymentUseCase;
    @Mock OrderQuery orderQuery;
    @Mock OrderResult orderResult;
    @Mock ShipmentService shipmentService;
    @Mock ShipmentQuery shipmentQuery;
    @Mock PurchaseConfirmedEventPublisher purchaseConfirmedEventPublisher;
    @Mock SellerOrderEventPublisher sellerOrderEventPublisher;
    @Mock OrderItemConfirmationService orderItemConfirmationService;
    @Mock ShippingFeeTableService shippingFeeTableService;
    @InjectMocks OrderService orderService;
    private OrderItemConfirmationService confirmationServiceUnderTest;
    private Orders savedOrder;

    @BeforeEach
    void setUpConfirmationService() {
        lenient().when(shippingFeeTableService.feeFor(any())).thenReturn(BigDecimal.valueOf(3_000));
        confirmationServiceUnderTest = new OrderItemConfirmationService(ordersRepository, ordersItemRepository,
                sellerOrderRepository, shipmentQuery, purchaseConfirmedEventPublisher, sellerOrderEventPublisher);
        // 트랜잭션 경계 순서만 보는 단위 테스트라 실제 커밋 없이 동기화 콜백만 돈다. CAS 동작은 통합 테스트에서 확인
        ReflectionTestUtils.setField(orderService, "orderPaymentTransactions", new OrderPaymentTransactions(
                new NoOpTransactionManager(), ordersRepository, sellerOrderRepository, ordersItemRepository,
                outboxPublisher, orderQuery,
                sellerOrderEventPublisher, cartService));
        OrderCancelTransactions cancelTransactions = new OrderCancelTransactions(new NoOpTransactionManager(),
                ordersRepository, sellerOrderRepository, ordersItemRepository, shipmentService, shipmentQuery,
                outboxPublisher, sellerOrderEventPublisher);
        ReflectionTestUtils.setField(orderService, "orderCancelTransactions", cancelTransactions);
        ReflectionTestUtils.setField(orderService, "orderCancelRefunder",
                new OrderCancelRefunder(paymentUseCase, cancelTransactions));
    }

    @Test
    @DisplayName("탈퇴한 회원은 장바구니 · 상품을 보기 전에 주문이 거절된다")
    void rejectsWithdrawnMemberBeforeAnything() {
        when(memberQuery.isWithdrawn(10L)).thenReturn(true);

        assertThatThrownBy(() -> orderService.create(10L, request(1L)))
            .isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.WITHDRAWN_MEMBER));
        verify(cartService, never()).getSelectCartItems(any(Long.class), any());
        verify(ordersRepository, never()).saveAndFlush(any());
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
        verify(productUseCase).reserve(99L, List.of(new ReservationItem(101L, money("10000"))));
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
    @DisplayName("배송비는 배송지 우편번호로 요금표에서 계산해 판매자 주문마다 한 번씩 붙인다 (#589)")
    void appliesShippingFeeFromTablePerSellerOrder() {
        when(shippingFeeTableService.feeFor("12345")).thenReturn(BigDecimal.valueOf(8_000));
        stubLookup(List.of(selected(1L, 101L, "10000"), selected(2L, 102L, "20000")),
                List.of(product(101L, 20L, "상품1", "10000", true),
                        product(102L, 30L, "상품2", "20000", true)));
        stubSavedOrder();
        stubInsufficientBalance();

        OrderCreateResponse response = orderService.create(10L, request(1L, 2L));

        ArgumentCaptor<Orders> orderCaptor = ArgumentCaptor.forClass(Orders.class);
        verify(ordersRepository).saveAndFlush(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getSellerOrders())
                .extracting(SellerOrder::getShippingFee)
                .allSatisfy(fee -> assertThat(fee).isEqualByComparingTo("8000"));
        assertThat(response.totalAmount()).isEqualByComparingTo("46000");
        verify(shippingFeeTableService).feeFor("12345");
    }

    @Test
    void generatesDifferentOrderNumbersForSeparateOrders() {
        stubLookup(List.of(selected(1L, 101L, "10000")),
                List.of(product(101L, 20L, "몬스테라", "10000", true)));
        stubSavedOrder();
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
    @DisplayName("가격 동의로 저장 가격을 현재 가격으로 바꾼 뒤 주문하면 그 가격으로 예약하고 주문이 만들어진다")
    void createsOrderAfterPriceConsent() {
        CartItem consented = selected(1L, 101L, "10000");
        consented.changePrice(money("12000")); // CartService.updatePrices가 하는 일
        stubLookup(List.of(consented), List.of(product(101L, 20L, "몬스테라", "12000", true)));
        stubSavedOrder();
        stubInsufficientBalance();

        OrderCreateResponse response = orderService.create(10L, request(1L));

        assertThat(response.totalAmount()).isEqualByComparingTo("15000");
        verify(productUseCase).reserve(99L, List.of(new ReservationItem(101L, money("12000"))));
        verify(cartService).removeSelectedItems(10L, List.of(1L));
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
        doThrow(new BusinessException(OrderErrorCode.PRODUCT_UNAVAILABLE))
                .when(productUseCase).reserve(eq(99L), anyList());

        assertThatThrownBy(() -> orderService.create(10L, request(1L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.PRODUCT_UNAVAILABLE));

        verify(productUseCase).reserve(99L, List.of(new ReservationItem(101L, money("10000"))));
        verify(cartService, never()).removeSelectedItems(any(Long.class), any());
        // 주문은 실패로 커밋하고, 예약이 안 됐으므로 해제 이벤트는 없다
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        verify(outboxPublisher, never()).publish(any());
        verify(paymentUseCase, never()).pay(any(), any(), any());
    }

    @Test
    void failsOrderWithoutRollbackWhenReservationTimesOut() {
        stubLookup(List.of(selected(1L, 101L, "10000")),
                List.of(product(101L, 20L, "몬스테라", "10000", true)));
        stubSavedOrder();
        doThrow(new InternalApiResultUnknownException("product", new java.net.SocketTimeoutException()))
                .when(productUseCase).reserve(eq(99L), anyList());

        OrderCreateResponse response = orderService.create(10L, request(1L));

        assertThat(response.status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(response.expiresAt()).isNull();
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        verify(paymentUseCase, never()).pay(any(), any(), any());
        verify(cartService, never()).removeSelectedItems(any(Long.class), any());
        verify(outboxPublisher).publish(new OrderFailed(99L));
    }

    @Test
    @DisplayName("[S-D04] 결제 응답 타임아웃이면 바로 보상하지 않고 결제 처리 중으로 두며, 장바구니는 비운다")
    void keepsOrderProcessingWhenPaymentTimesOut() {
        stubLookup(List.of(selected(1L, 101L, "10000")),
                List.of(product(101L, 20L, "몬스테라", "10000", true)));
        stubSavedOrder();
        when(paymentUseCase.pay(99L, 10L, money("13000")))
                .thenReturn(PaymentOutcome.unknown("TIMEOUT"));

        OrderCreateResponse response = orderService.create(10L, request(1L));

        assertThat(response.status()).isEqualTo(OrderStatus.PAYMENT_PROCESSING);
        assertThat(response.expiresAt()).isNull();
        assertThat(savedOrder.getAttemptNo()).isEqualTo(1);
        assertThat(savedOrder.getLastError()).isEqualTo("TIMEOUT");
        verify(outboxPublisher, never()).publish(any());
        verify(cartService).removeSelectedItems(10L, List.of(1L));
    }

    @Test
    @DisplayName("[S-D02] 결제 대기 주문이 만료되면 EXPIRED · 예약 해제 이벤트(OrderExpired) · 장바구니 복원")
    void expiresPendingOrderAndReleasesItsProducts() {
        Orders order = new Orders("ORD-test", 10L, "구매자", "010", "12345", "서울시", null, 1);
        SellerOrder sellerOrder = new SellerOrder(order, 20L);
        sellerOrder.addItems(List.of(new OrdersItem(sellerOrder, 101L, "몬스테라", money("10000"))));
        order.addSellerOrders(List.of(sellerOrder));
        ReflectionTestUtils.setField(order, "id", 99L);
        ReflectionTestUtils.setField(order, "createdAt", LocalDateTime.now().minusMinutes(11));
        when(ordersRepository.findByIdForUpdateSkipLocked(99L)).thenReturn(Optional.of(order));

        expirationService().expire(99L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        verify(ordersRepository).saveAndFlush(order);
        verify(outboxPublisher).publish(new OrderExpired(99L));
        verify(cartService).restoreItem(10L, 101L, money("10000"));
    }

    @Test
    @DisplayName("다른 파드가 잡았거나 이미 결제된 주문은 만료하지 않고 건너뛴다")
    void expirationSkipsOrderLockedByAnotherPodOrNoLongerPending() {
        Orders paid = pendingOrder(10L);
        paid.paid();
        when(ordersRepository.findByIdForUpdateSkipLocked(98L)).thenReturn(Optional.empty());
        when(ordersRepository.findByIdForUpdateSkipLocked(99L)).thenReturn(Optional.of(paid));

        expirationService().expire(98L);
        expirationService().expire(99L);

        assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(outboxPublisher, never()).publish(any());
        verify(cartService, never()).restoreItem(any(Long.class), any(Long.class), any());
    }

    @Test
    @DisplayName("잠근 뒤 보니 결제 처리 중 · 확인 필요이거나 아직 10분이 안 된 주문은 만료하지 않는다")
    void expirationRechecksStatusAndPaymentWindowAfterLocking() {
        Orders processing = pendingOrder(10L);
        ReflectionTestUtils.setField(processing, "createdAt", LocalDateTime.now().minusMinutes(11));
        ReflectionTestUtils.setField(processing, "status", OrderStatus.PAYMENT_PROCESSING);
        Orders reviewRequired = pendingOrder(10L);
        ReflectionTestUtils.setField(reviewRequired, "createdAt", LocalDateTime.now().minusMinutes(30));
        ReflectionTestUtils.setField(reviewRequired, "status", OrderStatus.REVIEW_REQUIRED);
        Orders notYetDue = pendingOrder(10L); // 생성 1분 전
        when(ordersRepository.findByIdForUpdateSkipLocked(97L)).thenReturn(Optional.of(processing));
        when(ordersRepository.findByIdForUpdateSkipLocked(98L)).thenReturn(Optional.of(reviewRequired));
        when(ordersRepository.findByIdForUpdateSkipLocked(99L)).thenReturn(Optional.of(notYetDue));

        expirationService().expire(97L);
        expirationService().expire(98L);
        expirationService().expire(99L);

        assertThat(processing.getStatus()).isEqualTo(OrderStatus.PAYMENT_PROCESSING);
        assertThat(reviewRequired.getStatus()).isEqualTo(OrderStatus.REVIEW_REQUIRED);
        assertThat(notYetDue.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        verify(ordersRepository, never()).saveAndFlush(any());
        verify(outboxPublisher, never()).publish(any());
        verify(cartService, never()).restoreItem(any(Long.class), any(Long.class), any());
    }

    @Test
    @DisplayName("한 주문의 만료가 실패해도 다음 주문은 계속 처리한다")
    void expirationContinuesWithNextOrderAfterFailure() {
        Orders first = pendingOrder(10L);
        Orders second = pendingOrder(10L);
        ReflectionTestUtils.setField(first, "id", 1L);
        ReflectionTestUtils.setField(second, "id", 2L);
        when(ordersRepository.findTop100ByStatusAndCreatedAtBeforeOrderByIdAsc(
                eq(OrderStatus.PAYMENT_PENDING), any(LocalDateTime.class))).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("일시적 실패")).when(orderExpirationService).expire(1L);

        orderService.expirePendingOrders();

        verify(orderExpirationService).expire(1L);
        verify(orderExpirationService).expire(2L);
    }

    private OrderExpirationService expirationService() {
        return new OrderExpirationService(ordersRepository, outboxPublisher, cartService);
    }

    @Test
    @DisplayName("[S-D03] 결제 성공이면 주문 PAID와 판매 완료 이벤트(OrderPaid)를 Outbox에 남기고, 배송은 판매자 수락 전이라 만들지 않는다")
    void paysOrderAndMarksReservedProductsSoldOut() {
        stubLookup(List.of(selected(1L, 101L, "10000")),
                List.of(product(101L, 20L, "몬스테라", "10000", true)));
        stubSavedOrder();
        when(paymentUseCase.pay(99L, 10L, money("13000"))).thenReturn(PaymentOutcome.paid());
        when(orderQuery.getOrder(99L)).thenReturn(orderResult);

        OrderCreateResponse response = orderService.create(10L, request(1L));

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(response.expiresAt()).isNull();
        verify(outboxPublisher).publish(new OrderPaid(99L));
        verify(shipmentService, never()).createShipment(any(), any());
        verify(cartService).removeSelectedItems(10L, List.of(1L));
    }

    @Test
    void repaysPendingOrderBeforeExpiry() {
        stubOrderRows(pendingOrder(10L));
        when(paymentUseCase.pay(99L, 10L, money("13000"))).thenReturn(PaymentOutcome.paid());
        when(orderQuery.getOrder(99L)).thenReturn(orderResult);

        OrderCreateResponse response = orderService.repayment(10L, 99L);

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(response.expiresAt()).isNull();
        verify(outboxPublisher).publish(new OrderPaid(99L));
        verify(shipmentService, never()).createShipment(any(), any());
        verify(cartService, never()).removeSelectedItems(any(Long.class), any());
        verify(cartService, never()).restoreItem(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("[S-A07] 남의 주문을 재결제하면 ORDER_NOT_FOUND(404), 결제를 부르지 않음")
    void rejectsRepaymentFromAnotherMember() {
        when(ordersRepository.findById(99L)).thenReturn(Optional.of(pendingOrder(10L)));

        assertThatThrownBy(() -> orderService.repayment(20L, 99L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));
        verify(paymentUseCase, never()).pay(any(), any(), any());
    }

    @Test
    @DisplayName("S-D10] 결제 기한 10분이 지나면 아직 PAYMENT_PENDING이어도(만료 스케줄러 전) 결제 처리 중으로 바꾸지 않고 PAYMENT_WINDOW_EXPIRED, 결제를 부르지 않음")
    void rejectsRepaymentAfterPaymentWindow() {
        Orders order = pendingOrder(10L);
        ReflectionTestUtils.setField(order, "createdAt", LocalDateTime.now().minusMinutes(11));
        stubOrderRows(order);

        assertThatThrownBy(() -> orderService.repayment(10L, 99L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.PAYMENT_WINDOW_EXPIRED));
        verify(paymentUseCase, never()).pay(any(), any(), any());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.getAttemptNo()).isZero();
    }

    @Test
    @DisplayName("[S-D02] 재결제 때 예치금이 부족하면 결제 대기를 유지하고 기한은 주문 후 10분 그대로")
    void keepsPendingOrderWhenRepaymentBalanceIsInsufficient() {
        Orders order = pendingOrder(10L);
        stubOrderRows(order);
        when(paymentUseCase.pay(99L, 10L, money("13000")))
                .thenReturn(PaymentOutcome.insufficientBalance());

        OrderCreateResponse response = orderService.repayment(10L, 99L);

        assertThat(response.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(response.expiresAt()).isEqualTo(order.getCreatedAt().plusMinutes(10));
        verify(outboxPublisher, never()).publish(any(OrderPaid.class));
        verify(shipmentService, never()).createShipment(any(), any());
        verify(cartService, never()).removeSelectedItems(any(Long.class), any());
    }

    @Test
    @DisplayName("결제 처리 중인 주문을 다시 결제하면 결제를 부르지 않고 ORDER_NOT_PAYABLE")
    void rejectsRepaymentWhilePaymentIsProcessing() {
        Orders order = pendingOrder(10L);
        ReflectionTestUtils.setField(order, "status", OrderStatus.PAYMENT_PROCESSING);
        stubOrderRows(order);

        assertThatThrownBy(() -> orderService.repayment(10L, 99L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_PAYABLE));
        verify(paymentUseCase, never()).pay(any(), any(), any());
    }

    @Test
    @DisplayName("재결제가 확정 실패(4xx)면 주문 실패 + order-failed, create가 지운 장바구니를 주문 당시 가격으로 되돌린다")
    void failsOrderWhenRepaymentIsRejected() {
        Orders order = pendingOrderWithItem(10L);
        stubOrderRows(order);
        when(paymentUseCase.pay(99L, 10L, money("13000"))).thenReturn(PaymentOutcome.rejected("HTTP_403_FORBIDDEN"));

        OrderCreateResponse response = orderService.repayment(10L, 99L);

        assertThat(response.status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(order.getLastError()).isEqualTo("HTTP_403_FORBIDDEN");
        verify(outboxPublisher).publish(new OrderFailed(99L));
        verify(cartService, never()).removeSelectedItems(any(Long.class), any());
        verify(cartService).restoreItem(10L, 101L, money("10000"));
    }

    @Test
    @DisplayName("재결제 요청이 payment에 닿지 않으면(연결 실패) 확정 실패로 주문 실패 + 장바구니 복원")
    void failsOrderAndRestoresCartWhenRepaymentIsNotDelivered() {
        Orders order = pendingOrderWithItem(10L);
        stubOrderRows(order);
        when(paymentUseCase.pay(99L, 10L, money("13000"))).thenReturn(PaymentOutcome.notDelivered());

        OrderCreateResponse response = orderService.repayment(10L, 99L);

        assertThat(response.status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(order.getLastError()).isEqualTo("CONNECTION_FAILED");
        verify(outboxPublisher).publish(new OrderFailed(99L));
        verify(cartService).restoreItem(10L, 101L, money("10000"));
    }

    @Test
    @DisplayName("재결제가 잔액 부족이면 결제 대기로 두고 장바구니는 바꾸지 않는다")
    void keepsPendingWithoutTouchingCartWhenRepaymentBalanceIsInsufficient() {
        Orders order = pendingOrderWithItem(10L);
        stubOrderRows(order);
        when(paymentUseCase.pay(99L, 10L, money("13000"))).thenReturn(PaymentOutcome.insufficientBalance());

        OrderCreateResponse response = orderService.repayment(10L, 99L);

        assertThat(response.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        verify(cartService, never()).restoreItem(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("[S-D12] 결제 응답을 기다리는 사이 다른 시도가 선점했으면 늦게 온 결과는 반영하지 않는다")
    void ignoresStaleOutcomeWhenAnotherAttemptTookOver() {
        Orders order = pendingOrder(10L);
        stubOrderRows(order);
        when(paymentUseCase.pay(99L, 10L, money("13000"))).thenAnswer(invocation -> {
            ReflectionTestUtils.setField(order, "attemptNo", 2); // 복구가 새 시도로 선점
            return PaymentOutcome.rejected("WALLET_NOT_FOUND");
        });

        OrderCreateResponse response = orderService.repayment(10L, 99L);

        assertThat(response.status()).isEqualTo(OrderStatus.PAYMENT_PROCESSING);
        verify(outboxPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("결과 반영(TX3)이 DB 오류로 실패해도 결제 처리 중으로 남기고 장바구니는 비운다")
    void leavesOrderProcessingWhenApplyingOutcomeFails() {
        stubLookup(List.of(selected(1L, 101L, "10000")),
                List.of(product(101L, 20L, "몬스테라", "10000", true)));
        stubSavedOrder();
        when(paymentUseCase.pay(99L, 10L, money("13000"))).thenReturn(PaymentOutcome.paid());
        when(ordersRepository.applyPaymentAttempt(eq(99L), anyInt(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("DB 오류"));

        OrderCreateResponse response = orderService.create(10L, request(1L));

        assertThat(response.status()).isEqualTo(OrderStatus.PAYMENT_PROCESSING);
        verify(outboxPublisher, never()).publish(any());
        verify(cartService).removeSelectedItems(10L, List.of(1L));
    }

    @Test
    @DisplayName("첫 결제 요청이 안 닿았는데 결과 반영(TX3)도 실패하면, 결제 처리 중으로 남아 복구가 결제할 수 있으므로 장바구니를 비운다")
    void removesCartWhenNotDeliveredButApplyingOutcomeFails() {
        stubLookup(List.of(selected(1L, 101L, "10000")),
                List.of(product(101L, 20L, "몬스테라", "10000", true)));
        stubSavedOrder();
        when(paymentUseCase.pay(99L, 10L, money("13000"))).thenReturn(PaymentOutcome.notDelivered());
        when(ordersRepository.applyPaymentAttempt(eq(99L), anyInt(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("DB 오류"));

        OrderCreateResponse response = orderService.create(10L, request(1L));

        assertThat(response.status()).isEqualTo(OrderStatus.PAYMENT_PROCESSING);
        verify(outboxPublisher, never()).publish(any());
        verify(cartService).removeSelectedItems(10L, List.of(1L));
    }

    @Test
    @DisplayName("장바구니 비우기가 실패해도 주문 응답은 그대로 나간다")
    void cartRemovalFailureDoesNotAffectOrder() {
        stubLookup(List.of(selected(1L, 101L, "10000")),
                List.of(product(101L, 20L, "몬스테라", "10000", true)));
        stubSavedOrder();
        stubInsufficientBalance();
        doThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND))
                .when(cartService).removeSelectedItems(10L, List.of(1L));

        OrderCreateResponse response = orderService.create(10L, request(1L));

        assertThat(response.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    @Test
    void failsOrderWithoutRollbackWhenWalletDoesNotExist() {
        stubLookup(List.of(selected(1L, 101L, "10000")),
                List.of(product(101L, 20L, "몬스테라", "10000", true)));
        stubSavedOrder();
        when(paymentUseCase.pay(99L, 10L, money("13000"))).thenReturn(PaymentOutcome.rejected("WALLET_NOT_FOUND"));

        assertFailedOrderCommitted(orderService.create(10L, request(1L)));
    }

    @Test
    void failsOrderWithoutRollbackWhenPaymentIsUnreachable() {
        stubLookup(List.of(selected(1L, 101L, "10000")),
                List.of(product(101L, 20L, "몬스테라", "10000", true)));
        stubSavedOrder();
        when(paymentUseCase.pay(99L, 10L, money("13000")))
                .thenReturn(PaymentOutcome.notDelivered());

        assertFailedOrderCommitted(orderService.create(10L, request(1L)));
    }

    @Test
    @DisplayName("[S-D07] 결제 5xx는 결과 불명으로 결제 처리 중을 유지한다")
    void keepsOrderProcessingWhenPaymentReturnsServerError() {
        stubLookup(List.of(selected(1L, 101L, "10000")),
                List.of(product(101L, 20L, "몬스테라", "10000", true)));
        stubSavedOrder();
        when(paymentUseCase.pay(99L, 10L, money("13000")))
                .thenReturn(PaymentOutcome.unknown("HTTP_500"));

        OrderCreateResponse response = orderService.create(10L, request(1L));

        assertThat(response.status()).isEqualTo(OrderStatus.PAYMENT_PROCESSING);
        assertThat(savedOrder.getLastError()).isEqualTo("HTTP_500");
        verify(outboxPublisher, never()).publish(any());
    }

    @Test
    void failsOrderWithoutEventWhenProductIsUnreachableBeforeReservation() {
        stubLookup(List.of(selected(1L, 101L, "10000")),
                List.of(product(101L, 20L, "몬스테라", "10000", true)));
        stubSavedOrder();
        doThrow(new InternalApiUnavailableException("product", new java.net.ConnectException()))
                .when(productUseCase).reserve(eq(99L), anyList());

        assertThatThrownBy(() -> orderService.create(10L, request(1L)))
                .isInstanceOf(InternalApiUnavailableException.class);
        verify(paymentUseCase, never()).pay(any(), any(), any());
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        verify(outboxPublisher, never()).publish(any());
    }

    // 예약을 부른 뒤라 예외 없이 PAYMENT_FAILED로 돌려줘야 주문 행이 커밋된다
    private void assertFailedOrderCommitted(OrderCreateResponse response) {
        assertThat(response.status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(response.expiresAt()).isNull();
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        verify(outboxPublisher).publish(new OrderFailed(99L));
        verify(outboxPublisher, never()).publish(any(OrderPaid.class));
        verify(cartService, never()).removeSelectedItems(any(Long.class), any());
        verify(cartService, never()).restoreItem(anyLong(), anyLong(), any()); // 지우지 않았으니 되돌릴 것도 없음
    }

    @Test
    @DisplayName("[S-A07] 남의 주문을 취소하면 ORDER_NOT_FOUND(404), 환불하지 않음")
    void rejectsCancelFromAnotherMember() {
        when(ordersRepository.findLockedById(99L)).thenReturn(Optional.of(pendingOrder(10L)));

        assertThatThrownBy(() -> orderService.cancel(20L, 99L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));
        verify(paymentUseCase, never()).refundCancelledItem(any(), any(), any(), any());
        verify(paymentUseCase, never()).refundShippingFee(any(), any(), any(), any());
    }

    @Test
    @DisplayName("[S-D06] 이미 결제된 주문에 결제를 다시 요청하면 다시 차감하지 않고 PAID")
    void returnsPaidOrderWithoutChargingAgainOnRepeatedRequest() {
        Orders order = pendingOrder(10L);
        order.paid();
        when(ordersRepository.findById(99L)).thenReturn(Optional.of(order));

        OrderCreateResponse response = orderService.repayment(10L, 99L);

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        verify(paymentUseCase, never()).pay(any(), any(), any());
        verify(outboxPublisher, never()).publish(any(OrderPaid.class));
        verify(shipmentService, never()).createShipment(any(), any());
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
        when(shipmentQuery.findBySellerOrderId(7L))
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
        when(shipmentQuery.findBySellerOrderId(7L))
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
        when(shipmentQuery.findBySellerOrderId(7L))
                .thenReturn(new ShipmentResult(8L, "BOOKED", null));

        assertThatThrownBy(() -> confirmationServiceUnderTest.confirm(10L, item.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.SHIPMENT_NOT_DELIVERED));
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.NORMAL);
        verify(purchaseConfirmedEventPublisher, never()).publishAfterCommit(any());
    }

    @Test
    @DisplayName("[S-A07] 남의 주문상품을 구매 확정하면 404, 확정하지 않음")
    void rejectsConfirmationForAnotherBuyer() {
        OrdersItem item = stubConfirmableItem();

        assertThatThrownBy(() -> confirmationServiceUnderTest.confirm(20L, item.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_ITEM_NOT_FOUND));
        verify(shipmentQuery, never()).findBySellerOrderId(any());
    }

    @Test
    void rejectsConfirmationWhenOrderIsNotPaid() {
        OrdersItem item = stubConfirmableItem();
        ReflectionTestUtils.setField(item.getSellerOrder().getOrder(), "status", OrderStatus.PAYMENT_PENDING);

        assertThatThrownBy(() -> confirmationServiceUnderTest.confirm(10L, item.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_PAID));
        verify(shipmentQuery, never()).findBySellerOrderId(any());
    }

    @Test
    void repeatedItemConfirmationIsIdempotentEvenAfterOrderCompletes() {
        OrdersItem item = stubConfirmableItem();
        when(shipmentQuery.findBySellerOrderId(7L))
                .thenReturn(new ShipmentResult(8L, "DELIVERED", LocalDateTime.now()));

        confirmationServiceUnderTest.confirm(10L, item.getId());
        confirmationServiceUnderTest.confirm(10L, item.getId());

        assertThat(item.getSellerOrder().getOrder().getStatus()).isEqualTo(OrderStatus.COMPLETED);
        verify(shipmentQuery).findBySellerOrderId(7L);
        verify(purchaseConfirmedEventPublisher).publishAfterCommit(any(PurchaseConfirmedEvent.class));
    }

    @Test
    void autoConfirmationSkipsReturnRequestedItemAfterCandidateLookup() {
        OrdersItem item = stubConfirmableItem();
        ReflectionTestUtils.setField(item, "status", OrderItemStatus.RETURN_REQUESTED);

        autoConfirm(item);

        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.RETURN_REQUESTED);
        verify(shipmentQuery, never()).findBySellerOrderId(any());
        verify(purchaseConfirmedEventPublisher, never()).publishAfterCommit(any());
    }

    @Test
    void autoConfirmationSkipsOrderThatIsNoLongerPaid() {
        OrdersItem item = stubConfirmableItem();
        ReflectionTestUtils.setField(item.getSellerOrder().getOrder(), "status", OrderStatus.CANCELLED);

        autoConfirm(item);

        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.NORMAL);
        verify(shipmentQuery, never()).findBySellerOrderId(any());
    }

    @Test
    void autoConfirmationSkipsDeliveryWithoutCompletionTime() {
        OrdersItem item = stubConfirmableItem();
        when(shipmentQuery.findBySellerOrderId(7L))
                .thenReturn(new ShipmentResult(8L, "DELIVERED", null));

        autoConfirm(item);

        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.NORMAL);
        verify(purchaseConfirmedEventPublisher, never()).publishAfterCommit(any());
    }

    @Test
    void doesNotPublishSettlementEventForZeroSaleAmount() {
        OrdersItem item = stubConfirmableItem();
        ReflectionTestUtils.setField(item.getSellerOrder(), "settlementBaseAmount", BigDecimal.ZERO);
        when(shipmentQuery.findBySellerOrderId(7L))
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
    void autoConfirmationGroupsItemsByOrderAndContinuesWithNextOrderAfterFailure() {
        when(ordersItemRepository.findAutoConfirmTargets(eq(OrderStatus.PAID), eq(OrderItemStatus.NORMAL),
                eq(ShipmentStatus.DELIVERED), any(LocalDateTime.class), eq(ShipmentDirection.OUTBOUND)))
                .thenReturn(List.of(new AutoConfirmTarget(1L, 10L, 100L),
                        new AutoConfirmTarget(1L, 10L, 101L),
                        new AutoConfirmTarget(2L, 20L, 200L)));
        doThrow(new IllegalStateException("일시적 실패"))
                .when(orderItemConfirmationService).autoConfirmOrder(1L, List.of(100L, 101L));

        orderService.autoConfirmDeliveredItems();

        verify(orderItemConfirmationService).autoConfirmOrder(1L, List.of(100L, 101L));
        verify(orderItemConfirmationService).autoConfirmOrder(2L, List.of(200L));
    }

    @Test
    void autoConfirmationSkipsOrderLockedByAnotherPod() {
        when(ordersRepository.findByIdForUpdateSkipLocked(99L)).thenReturn(Optional.empty());

        confirmationServiceUnderTest.autoConfirmOrder(99L, List.of(11L));

        verify(ordersItemRepository, never()).findOrderIdByItemId(any());
        verify(sellerOrderEventPublisher, never()).publishAfterCommit(any(OrderItemAutoConfirmedEvent.class));
    }

    private void autoConfirm(OrdersItem item) {
        when(ordersRepository.findByIdForUpdateSkipLocked(99L))
                .thenReturn(Optional.of(item.getSellerOrder().getOrder()));
        confirmationServiceUnderTest.autoConfirmOrder(99L, List.of(item.getId()));
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
            ReflectionTestUtils.setField(order, "createdAt", LocalDateTime.now());
            savedOrder = order;
            return order;
        });
        stubOrderRowsLazily();
    }

    private void stubOrderRows(Orders order) {
        savedOrder = order;
        stubOrderRowsLazily();
    }

    // 조건부 UPDATE(CAS)를 savedOrder에 흉내 낸다. 실제 SQL 동작은 통합 테스트에서 확인한다
    private void stubOrderRowsLazily() {
        lenient().when(ordersRepository.findById(99L)).thenAnswer(invocation -> Optional.of(savedOrder));
        lenient().when(ordersRepository.findLockedById(99L)).thenAnswer(invocation -> Optional.of(savedOrder));
        lenient().when(ordersRepository.startPaymentAttempt(eq(99L), any(), any(),
                eq(OrderStatus.PAYMENT_PENDING), eq(OrderStatus.PAYMENT_PROCESSING))).thenAnswer(invocation -> {
            LocalDateTime createdAfter = invocation.getArgument(2);
            if (savedOrder.getStatus() != OrderStatus.PAYMENT_PENDING || !savedOrder.getCreatedAt().isAfter(createdAfter)) {
                return 0;
            }
            ReflectionTestUtils.setField(savedOrder, "status", OrderStatus.PAYMENT_PROCESSING);
            ReflectionTestUtils.setField(savedOrder, "attemptNo", savedOrder.getAttemptNo() + 1);
            return 1;
        });
        lenient().when(ordersRepository.applyPaymentAttempt(eq(99L), anyInt(), any(), any(), any(),
                eq(OrderStatus.PAYMENT_PROCESSING))).thenAnswer(invocation -> {
            int attemptNo = invocation.getArgument(1);
            if (savedOrder.getStatus() != OrderStatus.PAYMENT_PROCESSING || savedOrder.getAttemptNo() != attemptNo) {
                return 0;
            }
            ReflectionTestUtils.setField(savedOrder, "status", invocation.getArgument(2));
            ReflectionTestUtils.setField(savedOrder, "lastError", invocation.getArgument(3));
            return 1;
        });
    }

    // 실제 트랜잭션 없이 커밋 · 동기화 콜백만 도는 매니저
    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    private void stubInsufficientBalance() {
        when(paymentUseCase.pay(eq(99L), eq(10L), any(BigDecimal.class)))
                .thenReturn(PaymentOutcome.insufficientBalance());
    }

    // 재결제 장바구니 복원용: 상품 1개(101, 10000원) + 배송비 3000원 = 13000원
    private Orders pendingOrderWithItem(long buyerId) {
        Orders order = pendingOrder(buyerId);
        SellerOrder sellerOrder = new SellerOrder(order, 20L);
        sellerOrder.addItems(List.of(new OrdersItem(sellerOrder, 101L, "몬스테라", money("10000"))));
        sellerOrder.setShippingFee(money("3000"));
        order.addSellerOrders(List.of(sellerOrder));
        return order;
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
                purchasable ? ProductStatus.ACTIVE : ProductStatus.SOLD_OUT, false, null, null, null);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}

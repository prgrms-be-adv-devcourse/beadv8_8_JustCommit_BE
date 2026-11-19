package com.justcommit.backend.market;

import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.cart.domain.Cart;
import com.justcommit.backend.market.cart.domain.CartItem;
import com.justcommit.backend.market.cart.infrastructure.CartItemRepository;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.OrdersItemRepository;
import com.justcommit.backend.market.order.infrastructure.SellerOrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.check_nullability=false")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class CartAndOrderPersistenceIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private OrdersRepository ordersRepository;

    @Autowired
    private SellerOrderRepository sellerOrderRepository;

    @Autowired
    private OrdersItemRepository ordersItemRepository;

    @Autowired
    private CartItemRepository cartItemRepository;

    @Test
    void findsPaymentPendingOrderByExpirationCutoffWithOptionalAddress2() {
        Orders order = new Orders("ORD-optional-address", 101L, "홍길동", "010", "06236", "주소1", null, 1);
        entityManager.persist(order);
        entityManager.flush();

        List<Orders> pending = ordersRepository.findTop100ByStatusAndCreatedAtBeforeOrderByIdAsc(
                OrderStatus.PAYMENT_PENDING, LocalDateTime.now().plusMinutes(1));

        assertThat(pending).extracting(Orders::getId).contains(order.getId());
        assertThat(order.getShipAddress2()).isNull();
    }

    @Test
    void pagesOrdersByOwnerAndBatchLoadsTheirItems() {
        Orders first = order("ORD-read-first");
        SellerOrder firstSeller = new SellerOrder(first, 201L);
        firstSeller.addItems(List.of(new OrdersItem(firstSeller, 1001L, "몬스테라", new BigDecimal("10000"))));
        first.addSellerOrders(List.of(firstSeller));

        Orders other = new Orders("ORD-read-other", 102L, "다른 구매자", "010", "12345", "서울", null, 1);
        SellerOrder otherSeller = new SellerOrder(other, 202L);
        otherSeller.addItems(List.of(new OrdersItem(otherSeller, 1002L, "선인장", new BigDecimal("20000"))));
        other.addSellerOrders(List.of(otherSeller));

        entityManager.persist(first);
        entityManager.persist(other);
        entityManager.flush();
        Long firstId = first.getId();
        Long firstSellerId = firstSeller.getId();
        entityManager.clear();

        var pageable = PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        assertThat(ordersRepository.findByBuyerId(101L, pageable).getContent())
                .extracting(Orders::getId).containsExactly(firstId);
        assertThat(ordersRepository.findByIdAndBuyerId(firstId, 102L)).isEmpty();
        assertThat(sellerOrderRepository.findBySellerId(201L, pageable).getContent())
                .extracting(SellerOrder::getId).containsExactly(firstSellerId);
        assertThat(sellerOrderRepository.findByIdAndSellerId(firstSellerId, 202L)).isEmpty();
        assertThat(ordersItemRepository.findByOrderIds(List.of(firstId)))
                .extracting(OrdersItem::getProductName).containsExactly("몬스테라");
        assertThat(ordersItemRepository.findBySellerOrderIds(List.of(firstSellerId)))
                .extracting(OrdersItem::getProductName).containsExactly("몬스테라");
    }

    @Test
    void findsRecentConfirmedProductIdsOfBuyerNewestFirst() {
        Orders order = order("ORD-recent-confirmed");
        // 확정 1개 + 취소 1개: 취소된 주문상품은 빠짐
        SellerOrder now = new SellerOrder(order, 201L);
        OrdersItem confirmedItem = new OrdersItem(now, 1001L, "확정", new BigDecimal("10000"));
        OrdersItem cancelledItem = new OrdersItem(now, 1002L, "취소", new BigDecimal("10000"));
        now.addItems(List.of(confirmedItem, cancelledItem));
        ReflectionTestUtils.setField(cancelledItem, "status", OrderItemStatus.CANCELLED);
        confirmedItem.confirm();
        now.confirmed();
        SellerOrder yesterday = confirmedSellerOrder(order, 202L, 1004L);
        SellerOrder old = confirmedSellerOrder(order, 203L, 1003L);
        SellerOrder notConfirmed = new SellerOrder(order, 204L);
        notConfirmed.addItems(List.of(new OrdersItem(notConfirmed, 1006L, "배송 중", new BigDecimal("10000"))));
        order.addSellerOrders(List.of(now, yesterday, old, notConfirmed));

        Orders other = new Orders("ORD-recent-other", 102L, "다른 구매자", "010", "12345", "서울", null, 1);
        SellerOrder otherSeller = confirmedSellerOrder(other, 201L, 1005L);
        other.addSellerOrders(List.of(otherSeller));

        entityManager.persist(order);
        entityManager.persist(other);
        entityManager.flush();
        setConfirmedAt(yesterday, LocalDateTime.now().minusDays(1));
        setConfirmedAt(old, LocalDateTime.now().minusDays(100));
        entityManager.clear();

        LocalDateTime since = LocalDateTime.now().minusDays(90);
        assertThat(ordersItemRepository.findRecentProductIdsByBuyer(101L, OrderItemStatus.CONFIRMED, since,
                PageRequest.of(0, 20))).containsExactly(1001L, 1004L);
        assertThat(ordersItemRepository.findRecentProductIdsByBuyer(101L, OrderItemStatus.CONFIRMED, since,
                PageRequest.of(0, 1))).containsExactly(1001L);
    }

    @Test
    void findsCartProductIdsOfMember() {
        Cart cart = new Cart(401L);
        cart.addItem(new CartItem(cart, 2001L, new BigDecimal("10000")));
        cart.addItem(new CartItem(cart, 2002L, new BigDecimal("10000")));
        Cart other = new Cart(402L);
        other.addItem(new CartItem(other, 2003L, new BigDecimal("10000")));
        entityManager.persist(cart);
        entityManager.persist(other);
        entityManager.flush();
        entityManager.clear();

        assertThat(cartItemRepository.findProductIdsByMemberId(401L)).containsExactly(2001L, 2002L);
        assertThat(cartItemRepository.findProductIdsByMemberId(499L)).isEmpty();
    }

    private static SellerOrder confirmedSellerOrder(Orders order, long sellerId, Long... productIds) {
        SellerOrder sellerOrder = new SellerOrder(order, sellerId);
        List<OrdersItem> items = Stream.of(productIds)
                .map(productId -> new OrdersItem(sellerOrder, productId, "상품 " + productId, new BigDecimal("10000")))
                .toList();
        sellerOrder.addItems(items);
        items.forEach(OrdersItem::confirm);
        sellerOrder.confirmed();
        return sellerOrder;
    }

    private void setConfirmedAt(SellerOrder sellerOrder, LocalDateTime confirmedAt) {
        entityManager.createNativeQuery("update seller_order set confirmed_at = :at where id = :id")
                .setParameter("at", confirmedAt)
                .setParameter("id", sellerOrder.getId())
                .executeUpdate();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @Test
    void savesAndReloadsCartItemsWithAuditTimestamps() {
        Cart cart = new Cart(101L);
        CartItem item = new CartItem(cart, 1001L, new BigDecimal("12000.00"));
        cart.addItem(item);

        entityManager.persist(cart);
        entityManager.flush();
        Long cartId = cart.getId();
        entityManager.clear();

        Cart reloaded = entityManager.find(Cart.class, cartId);

        assertThat(reloaded.getMemberId()).isEqualTo(101L);
        assertThat(reloaded.getItems()).singleElement().satisfies(reloadedItem -> {
            assertThat(reloadedItem.getCart().getId()).isEqualTo(cartId);
            assertThat(reloadedItem.getProductId()).isEqualTo(1001L);
            assertThat(reloadedItem.getPrice()).isEqualByComparingTo("12000.00");
            assertAuditTimestamp(reloadedItem.getCreatedAt(), reloadedItem.getUpdatedAt());
        });
        assertAuditTimestamp(reloaded.getCreatedAt(), reloaded.getUpdatedAt());
    }

    @Test
    void doesNotPersistTwoCartsForTheSameMember() {
        entityManager.persist(new Cart(301L));
        entityManager.flush();

        assertThatThrownBy(() -> {
            entityManager.persist(new Cart(301L));
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    void savesAndReloadsOrderHierarchyAndUpdatesAuditTimestamp() throws InterruptedException {
        Orders order = order("ORD-20261008-0001");
        SellerOrder sellerOrder = new SellerOrder(order, 201L);
        OrdersItem item = new OrdersItem(sellerOrder, 2001L, "키보드", new BigDecimal("25000.00"));
        sellerOrder.addItems(List.of(item));
        sellerOrder.setShippingFee(new BigDecimal("3000.00"));
        order.addSellerOrders(List.of(sellerOrder));

        entityManager.persist(order);
        entityManager.flush();
        Long orderId = order.getId();
        LocalDateTime previousUpdatedAt = sellerOrder.getUpdatedAt();

        Thread.sleep(5);
        sellerOrder.setShippingFee(new BigDecimal("4000.00"));
        order.calculateTotalAmount();
        entityManager.flush();
        entityManager.clear();

        Orders reloaded = entityManager.find(Orders.class, orderId);

        assertThat(reloaded.getOrderNo()).isEqualTo("ORD-20261008-0001");
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(reloaded.getTotalAmount()).isEqualByComparingTo("29000.00");
        assertAuditTimestamp(reloaded.getCreatedAt(), reloaded.getUpdatedAt());

        assertThat(reloaded.getSellerOrders()).singleElement().satisfies(reloadedSellerOrder -> {
            assertThat(reloadedSellerOrder.getOrder().getId()).isEqualTo(orderId);
            assertThat(reloadedSellerOrder.getSellerId()).isEqualTo(201L);
            assertThat(reloadedSellerOrder.getTotalAmount()).isEqualByComparingTo("25000.00");
            assertThat(reloadedSellerOrder.getShippingFee()).isEqualByComparingTo("4000.00");
            assertThat(reloadedSellerOrder.getUpdatedAt()).isAfter(previousUpdatedAt);
            assertAuditTimestamp(reloadedSellerOrder.getCreatedAt(), reloadedSellerOrder.getUpdatedAt());
            assertThat(reloadedSellerOrder.getItems()).singleElement().satisfies(reloadedItem -> {
                assertThat(reloadedItem.getSellerOrder().getId()).isEqualTo(reloadedSellerOrder.getId());
                assertThat(reloadedItem.getProductId()).isEqualTo(2001L);
                assertThat(reloadedItem.getProductName()).isEqualTo("키보드");
                assertThat(reloadedItem.getStatus()).isEqualTo(OrderItemStatus.NORMAL);
                assertAuditTimestamp(reloadedItem.getCreatedAt(), reloadedItem.getUpdatedAt());
            });
        });
    }

    @Test
    void rejectsDuplicateCartMemberId() {
        entityManager.persist(new Cart(101L));
        entityManager.flush();

        assertPostgresConstraintViolation("23505", () -> {
            entityManager.persist(new Cart(101L));
            entityManager.flush();
        });
    }

    @Test
    void rejectsDuplicateProductInSameCart() {
        Cart cart = persistCart(101L);
        CartItem firstItem = new CartItem(cart, 1001L, new BigDecimal("12000.00"));
        entityManager.persist(firstItem);
        entityManager.flush();

        assertPostgresConstraintViolation("23505", () -> {
            entityManager.persist(new CartItem(cart, 1001L, new BigDecimal("13000.00")));
            entityManager.flush();
        });
    }

    @Test
    void rejectsDuplicateOrderNo() {
        entityManager.persist(order("ORD-20261008-0001"));
        entityManager.flush();

        assertPostgresConstraintViolation("23505", () -> {
            entityManager.persist(order("ORD-20261008-0001"));
            entityManager.flush();
        });
    }

    @Test
    void rejectsDuplicateSellerInSameOrder() {
        Orders order = persistOrder("ORD-20261008-0001");
        entityManager.persist(new SellerOrder(order, 201L));
        entityManager.flush();

        assertPostgresConstraintViolation("23505", () -> {
            entityManager.persist(new SellerOrder(order, 201L));
            entityManager.flush();
        });
    }

    @Test
    void rejectsCartWithoutMemberId() {
        assertPostgresConstraintViolation("23502", () -> {
            entityManager.persist(new Cart(null));
            entityManager.flush();
        });
    }

    @ParameterizedTest(name = "cart item requires {0}")
    @MethodSource("invalidCartItemFactories")
    void rejectsCartItemWithoutRequiredValue(String ignored, Function<Cart, CartItem> factory) {
        Cart cart = persistCart(101L);
        assertPostgresConstraintViolation("23502", () -> {
            entityManager.persist(factory.apply(cart));
            entityManager.flush();
        });
    }

    @ParameterizedTest(name = "order requires {0}")
    @MethodSource("invalidOrderFactories")
    void rejectsOrderWithoutRequiredValue(String ignored, Function<String, Orders> factory) {
        assertPostgresConstraintViolation("23502", () -> {
            entityManager.persist(factory.apply("ORD-20261008-0001"));
            entityManager.flush();
        });
    }

    @ParameterizedTest(name = "seller order requires {0}")
    @MethodSource("invalidSellerOrderFactories")
    void rejectsSellerOrderWithoutRequiredValue(String ignored, Function<Orders, SellerOrder> factory) {
        Orders order = persistOrder("ORD-20261008-0001");
        assertPostgresConstraintViolation("23502", () -> {
            entityManager.persist(factory.apply(order));
            entityManager.flush();
        });
    }

    @ParameterizedTest(name = "order item requires {0}")
    @MethodSource("invalidOrdersItemFactories")
    void rejectsOrderItemWithoutRequiredValue(String ignored, Function<SellerOrder, OrdersItem> factory) {
        SellerOrder sellerOrder = persistSellerOrder("ORD-20261008-0001", 201L);
        assertPostgresConstraintViolation("23502", () -> {
            entityManager.persist(factory.apply(sellerOrder));
            entityManager.flush();
        });
    }

    private Cart persistCart(long memberId) {
        Cart cart = new Cart(memberId);
        entityManager.persist(cart);
        entityManager.flush();
        return cart;
    }

    private Orders persistOrder(String orderNo) {
        Orders order = order(orderNo);
        entityManager.persist(order);
        entityManager.flush();
        return order;
    }

    private SellerOrder persistSellerOrder(String orderNo, long sellerId) {
        Orders order = order(orderNo);
        SellerOrder sellerOrder = new SellerOrder(order, sellerId);
        order.addSellerOrders(List.of(sellerOrder));
        entityManager.persist(order);
        entityManager.flush();
        return sellerOrder;
    }

    private static Orders order(String orderNo) {
        return new Orders(
                orderNo,
                101L,
                "홍길동",
                "010-1234-5678",
                "06236",
                "서울 강남구 테헤란로 1",
                "101동 1001호",
                1
        );
    }

    private static Stream<Arguments> invalidCartItemFactories() {
        return Stream.of(
                Arguments.of("cart", (Function<Cart, CartItem>) cart -> new CartItem(null, 1001L, BigDecimal.ONE)),
                Arguments.of("productId", (Function<Cart, CartItem>) cart -> new CartItem(cart, null, BigDecimal.ONE)),
                Arguments.of("price", (Function<Cart, CartItem>) cart -> new CartItem(cart, 1001L, null))
        );
    }

    private static Stream<Arguments> invalidOrderFactories() {
        return Stream.of(
                Arguments.of("orderNo", (Function<String, Orders>) orderNo -> new Orders(null, 101L, "홍길동", "010", "06236", "주소1", "주소2", 1)),
                Arguments.of("buyerId", (Function<String, Orders>) orderNo -> new Orders(orderNo, null, "홍길동", "010", "06236", "주소1", "주소2", 1)),
                Arguments.of("recipientName", (Function<String, Orders>) orderNo -> new Orders(orderNo, 101L, null, "010", "06236", "주소1", "주소2", 1)),
                Arguments.of("recipientPhone", (Function<String, Orders>) orderNo -> new Orders(orderNo, 101L, "홍길동", null, "06236", "주소1", "주소2", 1)),
                Arguments.of("zipcode", (Function<String, Orders>) orderNo -> new Orders(orderNo, 101L, "홍길동", "010", null, "주소1", "주소2", 1)),
                Arguments.of("shipAddress1", (Function<String, Orders>) orderNo -> new Orders(orderNo, 101L, "홍길동", "010", "06236", null, "주소2", 1)),
                Arguments.of("itemCount", (Function<String, Orders>) orderNo -> new Orders(orderNo, 101L, "홍길동", "010", "06236", "주소1", "주소2", null))
        );
    }

    private static Stream<Arguments> invalidSellerOrderFactories() {
        return Stream.of(
                Arguments.of("order", (Function<Orders, SellerOrder>) order -> new SellerOrder(null, 201L)),
                Arguments.of("sellerId", (Function<Orders, SellerOrder>) order -> new SellerOrder(order, null))
        );
    }

    private static Stream<Arguments> invalidOrdersItemFactories() {
        return Stream.of(
                Arguments.of("sellerOrder", (Function<SellerOrder, OrdersItem>) sellerOrder -> new OrdersItem(null, 2001L, "키보드", BigDecimal.ONE)),
                Arguments.of("productId", (Function<SellerOrder, OrdersItem>) sellerOrder -> new OrdersItem(sellerOrder, null, "키보드", BigDecimal.ONE)),
                Arguments.of("productName", (Function<SellerOrder, OrdersItem>) sellerOrder -> new OrdersItem(sellerOrder, 2001L, null, BigDecimal.ONE)),
                Arguments.of("price", (Function<SellerOrder, OrdersItem>) sellerOrder -> new OrdersItem(sellerOrder, 2001L, "키보드", null))
        );
    }

    private static void assertAuditTimestamp(LocalDateTime createdAt, LocalDateTime updatedAt) {
        assertThat(createdAt).isNotNull();
        assertThat(updatedAt).isNotNull().isAfterOrEqualTo(createdAt);
    }

    private static void assertPostgresConstraintViolation(String expectedSqlState, Runnable action) {
        assertThatThrownBy(() -> action.run())
                .isInstanceOf(PersistenceException.class)
                .satisfies(exception -> {
                    SQLException sqlException = findSqlException(exception);
                    assertThat((Object) sqlException).isNotNull();
                    assertThat(sqlException.getSQLState()).isEqualTo(expectedSqlState);
                });
    }

    private static SQLException findSqlException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }
}

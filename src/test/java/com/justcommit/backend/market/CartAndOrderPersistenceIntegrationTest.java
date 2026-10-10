package com.justcommit.backend.market;

import com.justcommit.backend.common.config.JpaAuditingConfig;
import com.justcommit.backend.market.cart.domain.Cart;
import com.justcommit.backend.market.cart.domain.CartItem;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.check_nullability=false"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class CartAndOrderPersistenceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private OrdersRepository ordersRepository;

    @Test
    void findsPaymentPendingOrderByExpirationCutoffWithOptionalAddress2() {
        Orders order = new Orders("ORD-optional-address", 101L, "홍길동", "010", "06236", "주소1", null, 1);
        entityManager.persist(order);
        entityManager.flush();

        List<Orders> pending = ordersRepository.findByStatusAndCreatedAtBefore(
                OrderStatus.PAYMENT_PENDING, LocalDateTime.now().plusMinutes(1));

        assertThat(pending).extracting(Orders::getId).contains(order.getId());
        assertThat(order.getShipAddress2()).isNull();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
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

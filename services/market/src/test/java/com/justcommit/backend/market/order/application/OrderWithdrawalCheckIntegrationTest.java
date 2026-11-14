package com.justcommit.backend.market.order.application;

import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.presentation.internal.WithdrawalBlockingReason;
import com.justcommit.backend.market.order.presentation.internal.WithdrawalCheckResponse;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 탈퇴 확인 count 쿼리를 실제 PostgreSQL에서 확인 (진행 중 기준 · 중복 없이 세기)
@DataJpaTest(properties = "spring.jpa.properties.hibernate.check_nullability=false")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, OrderWithdrawalCheckService.class})
@Testcontainers(disabledWithoutDocker = true)
class OrderWithdrawalCheckIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final long MEMBER = 500L;
    private static final long OTHER = 900L;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private OrderWithdrawalCheckService service;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    @DisplayName("주문이 없는 회원은 차단하지 않고 건수 0")
    void noOrders() {
        WithdrawalCheckResponse result = service.check(MEMBER);

        assertThat(result.hasWithdrawalBlocker()).isFalse();
        assertThat(result.blockingReasons()).isEmpty();
        assertThat(result.details()).isEqualTo(new WithdrawalCheckResponse.Details(0, 0));
    }

    @Test
    @DisplayName("구매자로만 진행 중 주문이 있으면 ACTIVE_BUYER_ORDER, 주문상품이 여러 개여도 주문 하나로 센다")
    void buyerOnly() {
        // 결제 대기 주문 (판매자 두 명 · 상품 세 개) + 결제 완료 · 반품 요청 주문
        save(MEMBER, OrderStatus.PAYMENT_PENDING,
                seller(OTHER, OrderItemStatus.NORMAL, OrderItemStatus.NORMAL),
                seller(OTHER + 1, OrderItemStatus.NORMAL));
        save(MEMBER, OrderStatus.PAID, seller(OTHER, OrderItemStatus.CONFIRMED, OrderItemStatus.REFUND_REQUESTED));

        WithdrawalCheckResponse result = service.check(MEMBER);

        assertThat(result.hasWithdrawalBlocker()).isTrue();
        assertThat(result.blockingReasons()).containsExactly(WithdrawalBlockingReason.ACTIVE_BUYER_ORDER);
        assertThat(result.details()).isEqualTo(new WithdrawalCheckResponse.Details(2, 0));
    }

    @Test
    @DisplayName("판매자로만 진행 중 주문이 있으면 ACTIVE_SELLER_ORDER, 판매자 주문 단위로 센다")
    void sellerOnly() {
        save(OTHER, OrderStatus.PAID,
                seller(MEMBER, OrderItemStatus.NORMAL, OrderItemStatus.NORMAL),
                seller(OTHER + 1, OrderItemStatus.NORMAL));
        save(OTHER + 2, OrderStatus.PAYMENT_PENDING, seller(MEMBER, OrderItemStatus.NORMAL));

        WithdrawalCheckResponse result = service.check(MEMBER);

        assertThat(result.hasWithdrawalBlocker()).isTrue();
        assertThat(result.blockingReasons()).containsExactly(WithdrawalBlockingReason.ACTIVE_SELLER_ORDER);
        assertThat(result.details()).isEqualTo(new WithdrawalCheckResponse.Details(0, 2));
    }

    @Test
    @DisplayName("구매 · 판매 둘 다 진행 중이면 사유 두 개")
    void buyerAndSeller() {
        save(MEMBER, OrderStatus.PAID, seller(OTHER, OrderItemStatus.NORMAL));
        save(OTHER, OrderStatus.PAID, seller(MEMBER, OrderItemStatus.REFUND_REQUESTED));

        WithdrawalCheckResponse result = service.check(MEMBER);

        assertThat(result.hasWithdrawalBlocker()).isTrue();
        assertThat(result.blockingReasons()).containsExactly(
                WithdrawalBlockingReason.ACTIVE_BUYER_ORDER, WithdrawalBlockingReason.ACTIVE_SELLER_ORDER);
        assertThat(result.details()).isEqualTo(new WithdrawalCheckResponse.Details(1, 1));
    }

    @Test
    @DisplayName("구매 확정 · 취소 · 환불 완료 · 만료 · 결제 실패 주문만 있으면 차단하지 않는다")
    void finishedOrdersDoNotBlock() {
        // 구매자로
        save(MEMBER, OrderStatus.COMPLETED, seller(OTHER, OrderItemStatus.CONFIRMED));
        save(MEMBER, OrderStatus.CANCELLED, seller(OTHER, OrderItemStatus.CANCELLED));
        save(MEMBER, OrderStatus.EXPIRED, seller(OTHER, OrderItemStatus.NORMAL));
        save(MEMBER, OrderStatus.PAYMENT_FAILED, seller(OTHER, OrderItemStatus.NORMAL));
        // 결제 완료지만 주문상품이 모두 끝남 (확정 · 취소 · 환불 완료)
        save(MEMBER, OrderStatus.PAID,
                seller(OTHER, OrderItemStatus.CONFIRMED, OrderItemStatus.CANCELLED, OrderItemStatus.REFUNDED));
        // 판매자로
        save(OTHER, OrderStatus.COMPLETED, seller(MEMBER, OrderItemStatus.CONFIRMED));
        save(OTHER, OrderStatus.PAID, seller(MEMBER, OrderItemStatus.REFUNDED, OrderItemStatus.CANCELLED),
                seller(OTHER + 1, OrderItemStatus.NORMAL));
        save(OTHER, OrderStatus.EXPIRED, seller(MEMBER, OrderItemStatus.NORMAL));

        WithdrawalCheckResponse result = service.check(MEMBER);

        assertThat(result.hasWithdrawalBlocker()).isFalse();
        assertThat(result.blockingReasons()).isEmpty();
        assertThat(result.details()).isEqualTo(new WithdrawalCheckResponse.Details(0, 0));
    }

    // 판매자 한 명 몫의 주문상품 상태들
    private record SellerSpec(long sellerId, OrderItemStatus[] itemStatuses) {
    }

    private static SellerSpec seller(long sellerId, OrderItemStatus... itemStatuses) {
        return new SellerSpec(sellerId, itemStatuses);
    }

    private int orderSeq = 0;

    // 엔티티에 해당 상태로 바꾸는 메서드가 없는 상태(반품 요청 · 환불 완료 등)도 있어 저장 뒤 SQL로 상태를 맞춘다
    private void save(long buyerId, OrderStatus orderStatus, SellerSpec... sellers) {
        Orders order = new Orders("ORD-wd-" + (++orderSeq), buyerId, "구매자", "010", "12345", "서울", null, 1);
        List<SellerOrder> sellerOrders = new ArrayList<>();
        List<OrdersItem> allItems = new ArrayList<>();
        List<OrderItemStatus> allStatuses = new ArrayList<>();
        long productId = orderSeq * 100L;
        for (SellerSpec spec : sellers) {
            SellerOrder sellerOrder = new SellerOrder(order, spec.sellerId());
            List<OrdersItem> items = new ArrayList<>();
            for (OrderItemStatus status : spec.itemStatuses()) {
                OrdersItem item = new OrdersItem(sellerOrder, ++productId, "식물", new BigDecimal("10000"));
                items.add(item);
                allItems.add(item);
                allStatuses.add(status);
            }
            sellerOrder.addItems(items);
            sellerOrders.add(sellerOrder);
        }
        order.addSellerOrders(sellerOrders);
        entityManager.persist(order);
        entityManager.flush();

        entityManager.createNativeQuery("update orders set status = :status where id = :id")
                .setParameter("status", orderStatus.name())
                .setParameter("id", order.getId())
                .executeUpdate();
        for (int i = 0; i < allItems.size(); i++) {
            entityManager.createNativeQuery("update orders_item set status = :status where id = :id")
                    .setParameter("status", allStatuses.get(i).name())
                    .setParameter("id", allItems.get(i).getId())
                    .executeUpdate();
        }
        entityManager.clear();
    }
}

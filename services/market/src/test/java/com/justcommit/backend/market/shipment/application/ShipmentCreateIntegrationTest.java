package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.OrderResult;
import com.justcommit.backend.market.OrderShippingInfo;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.OrdersItemResult;
import com.justcommit.backend.market.SellerOrderResult;
import com.justcommit.backend.market.client.member.MemberAddress;
import com.justcommit.backend.market.client.member.MemberQuery;
import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingRequest;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingResponse;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierClient;
import com.justcommit.backend.market.shipment.infrastructure.kafka.ShipmentStatusChangedEventPublisher;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 결제 트랜잭션 안에서 배송을 만들 때 READY 행만 만들고 택배사는 부르지 않는지 실제 DB로 확인한다 (#475).
// 택배 예약은 예약 큐가 맡는다 → ShipmentBookingQueueIntegrationTest
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ShipmentService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 테스트가 직접 트랜잭션을 열고 닫는다
class ShipmentCreateIntegrationTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @MockitoBean CourierClient courierClient;
    @MockitoBean MemberQuery memberQuery;
    @MockitoBean ShipmentStatusChangedEventPublisher statusChangedEventPublisher;
    @MockitoBean OrderQuery orderQuery;

    @Autowired ShipmentService shipmentService;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        shipmentRepository.deleteAll();
    }

    @Test
    @DisplayName("[S-F01] 결제 트랜잭션이 커밋돼도 택배사를 부르지 않고 READY 배송(택배 주문번호 · 상품 요약)만 남긴다")
    void createsReadyWithoutCallingCourier() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> shipmentService.createShipments(order()));

        assertThat(shipmentRepository.findBySellerOrderId(7L)).hasValueSatisfying(shipment -> {
            assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.READY);
            assertThat(shipment.getCourierOrderNo()).startsWith("shipment-");
            assertThat(shipment.getItemSummary()).isEqualTo("몬스테라");
            assertThat(shipment.getNextAttemptAt()).isNull(); // 예약 큐가 바로 집는다
        });
        verifyNoInteractions(courierClient, memberQuery);
    }

    @Test
    @DisplayName("[S-F01] 결제 트랜잭션이 롤백되면 배송 행도 남지 않는다")
    void rollbackLeavesNothing() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            shipmentService.createShipments(order());
            status.setRollbackOnly(); // 결제 처리 중 에러로 롤백되는 상황
        });

        assertThat(shipmentRepository.findBySellerOrderId(7L)).isEmpty();
        verify(courierClient, never()).book(anyString(), any());
    }

    @Test
    @DisplayName("[S-F08] 택배사가 3초씩 늦어도 결제 쪽 배송 생성은 기다리지 않고, 예약은 큐가 나중에 끝낸다")
    void paymentDoesNotWaitForSlowCourier() {
        when(orderQuery.getShippingInfo(anyLong())).thenReturn(
                new OrderShippingInfo(9L, 10L, 20L, new BigDecimal("3000"), "구매자", "01000000000", "부산", null));
        when(memberQuery.getDefaultAddress(20L))
                .thenReturn(new MemberAddress(1L, "판매자", "01012345678", "12345", "서울", null));
        when(courierClient.book(anyString(), any(CourierBookingRequest.class))).thenAnswer(inv -> {
            Thread.sleep(3_000); // 택배사 지연
            return new CourierBookingResponse("404000000001", inv.getArgument(0), "RESERVED",
                    OffsetDateTime.parse("2026-11-16T10:00:00+09:00"));
        });

        long started = System.nanoTime();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> shipmentService.createShipments(order()));
        Duration paymentTook = Duration.ofNanos(System.nanoTime() - started);

        assertThat(paymentTook).isLessThan(Duration.ofSeconds(1));
        verify(courierClient, never()).book(anyString(), any());

        shipmentService.bookReadyShipments(); // 10초 예약 큐 한 번

        assertThat(shipmentRepository.findBySellerOrderId(7L))
                .hasValueSatisfying(shipment -> assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.BOOKED));
    }

    private OrderResult order() {
        SellerOrderResult sellerOrder = new SellerOrderResult(7L, 20L,
                List.of(new OrdersItemResult(11L, 101L, "몬스테라", new BigDecimal("10000"), OrderItemStatus.NORMAL)),
                new BigDecimal("10000"), null, new BigDecimal("3000"), null, null, null);
        return new OrderResult(9L, 10L, List.of(sellerOrder), new BigDecimal("13000"),
                "구매자", "01000000000", "12345", "부산", null, 1, OrderStatus.PAID);
    }
}

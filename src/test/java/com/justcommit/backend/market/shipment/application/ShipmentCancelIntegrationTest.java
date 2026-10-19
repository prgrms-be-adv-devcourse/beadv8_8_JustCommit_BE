package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.common.config.JpaAuditingConfig;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderResult;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.OrdersItemResult;
import com.justcommit.backend.market.SellerOrderResult;
import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingException;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingRequest;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingResponse;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierCancelException;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierCancelResult;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierClient;
import com.justcommit.backend.market.shipment.infrastructure.kafka.ShipmentStatusChangedEventPublisher;
import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.OrderShippingInfo;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import com.justcommit.backend.member.MemberAddress;
import com.justcommit.backend.member.MemberQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 주문 트랜잭션 안에서 배송을 취소할 때, 택배사 취소가 커밋 뒤에만 일어나는지 실제 DB로 확인한다.
@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ShipmentService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 테스트가 직접 트랜잭션을 열고 닫는다
class ShipmentCancelIntegrationTest {

    private static final String TRACKING_NO = "404000000001";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean CourierClient courierClient;
    @MockitoBean MemberQuery memberQuery;
    @MockitoBean ShipmentStatusChangedEventPublisher statusChangedEventPublisher;
    @MockitoBean OrderQuery orderQuery;

    @Autowired ShipmentService shipmentService;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void stubOrderInfo() {
        when(orderQuery.getShippingInfo(7L)).thenReturn(new OrderShippingInfo(9L, 10L, 20L, new BigDecimal("3000"), "구매자", "01000000000", "부산", null));
    }

    @AfterEach
    void cleanUp() {
        shipmentRepository.deleteAll();
    }

    @Test
    @DisplayName("예약된 배송을 취소하면 커밋 뒤에 택배사 취소를 부르고 완료를 기록한다")
    void cancelsBookedAfterCommit() {
        createBookedShipment();

        inTransaction(() -> {
            shipmentService.cancelShipment(7L);
            verify(courierClient, never()).cancel(anyString()); // 커밋 전에는 택배사를 부르지 않음
        });

        verify(courierClient).cancel(TRACKING_NO);
        assertThat(shipment()).satisfies(shipment -> {
            assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
            assertThat(shipment.getCancelledAt()).isNotNull();
            assertThat(shipment.getCourierCancelledAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("주문 트랜잭션이 롤백되면 배송은 그대로이고 택배사 취소도 부르지 않는다")
    void rollbackKeepsShipment() {
        createBookedShipment();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            shipmentService.cancelShipment(7L);
            status.setRollbackOnly();
        });

        verify(courierClient, never()).cancel(anyString());
        assertThat(shipment().getStatus()).isEqualTo(ShipmentStatus.BOOKED);
    }

    @Test
    @DisplayName("이미 취소된 배송을 다시 취소하면 택배사를 다시 부르지 않는다")
    void cancelTwiceCallsCourierOnce() {
        createBookedShipment();

        inTransaction(() -> shipmentService.cancelShipment(7L));
        inTransaction(() -> shipmentService.cancelShipment(7L));

        verify(courierClient, times(1)).cancel(TRACKING_NO);
    }

    @Test
    @DisplayName("수거된 배송은 취소를 거부한다")
    void pickedUpCannotBeCancelled() {
        createBookedShipment();
        inTransaction(() -> {
            Shipment shipment = shipment();
            shipment.applyCourierStatus(ShipmentStatus.PICKED_UP, TRACKING_NO, LocalDateTime.of(2026, 10, 16, 10, 3));
            shipmentRepository.save(shipment);
        });

        assertThatThrownBy(() -> inTransaction(() -> shipmentService.cancelShipment(7L)))
                .isInstanceOf(BusinessException.class);
        verify(courierClient, never()).cancel(anyString());
    }

    @Test
    @DisplayName("READY 배송을 취소하면 같은 멱등키로 송장번호를 받아 온 뒤 택배사에서 취소한다")
    void cancelsReadyByRebookingFirst() {
        when(memberQuery.getDefaultAddress(20L))
                .thenThrow(new IllegalStateException("일시적 실패")) // 결제 직후 예약 실패 → READY
                .thenReturn(seller());
        when(courierClient.book(anyString(), any(CourierBookingRequest.class))).thenReturn(bookingResponse());
        when(courierClient.cancel(TRACKING_NO)).thenReturn(CourierCancelResult.CANCELLED);
        inTransaction(() -> shipmentService.createShipments(order()));
        String courierOrderNo = shipment().getCourierOrderNo();

        inTransaction(() -> shipmentService.cancelShipment(7L));

        verify(courierClient).book(eq(courierOrderNo), any(CourierBookingRequest.class));
        verify(courierClient).cancel(TRACKING_NO);
        assertThat(shipment()).satisfies(shipment -> {
            assertThat(shipment.getTrackingNo()).isEqualTo(TRACKING_NO);
            assertThat(shipment.getCourierCancelledAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("READY 배송의 재예약을 택배사가 거부하면 택배사에 건이 없는 것이므로 서버 취소만으로 끝낸다")
    void cancelsReadyWhenCourierHasNothing() {
        when(memberQuery.getDefaultAddress(20L))
                .thenThrow(new IllegalStateException("일시적 실패"))
                .thenReturn(seller());
        when(courierClient.book(anyString(), any(CourierBookingRequest.class)))
                .thenThrow(new CourierBookingException("400", new RuntimeException(), false));
        inTransaction(() -> shipmentService.createShipments(order()));

        inTransaction(() -> shipmentService.cancelShipment(7L));

        verify(courierClient, never()).cancel(anyString());
        assertThat(shipment().getCourierCancelledAt()).isNotNull();
    }

    @Test
    @DisplayName("택배사 취소가 실패하면 기록하지 않고, 대사 작업이 다시 취소한다")
    void retriesFailedCourierCancel() {
        createBookedShipment();
        when(courierClient.cancel(TRACKING_NO))
                .thenThrow(new CourierCancelException("타임아웃", new RuntimeException()))
                .thenReturn(CourierCancelResult.CANCELLED);

        inTransaction(() -> shipmentService.cancelShipment(7L));
        assertThat(shipment().getCourierCancelledAt()).isNull();

        shipmentService.retryCourierCancels(LocalDateTime.now().plusDays(1));

        verify(courierClient, times(2)).cancel(TRACKING_NO);
        assertThat(shipment().getCourierCancelledAt()).isNotNull();
    }

    @Test
    @DisplayName("택배사에서 이미 수거되어 취소가 거부되면 다시 시도하지 않는다")
    void notCancellableIsNotRetried() {
        createBookedShipment();
        when(courierClient.cancel(TRACKING_NO)).thenReturn(CourierCancelResult.NOT_CANCELLABLE);

        inTransaction(() -> shipmentService.cancelShipment(7L));
        shipmentService.retryCourierCancels(LocalDateTime.now().plusDays(1));

        verify(courierClient, times(1)).cancel(TRACKING_NO);
    }

    @Test
    @DisplayName("예약하는 사이 주문 취소가 먼저 저장되면 예약이 취소를 덮어쓰지 않고, 택배사 예약도 취소된다")
    void cancelWinsOverConcurrentBooking() {
        when(memberQuery.getDefaultAddress(20L)).thenReturn(seller());
        when(courierClient.cancel(TRACKING_NO)).thenReturn(CourierCancelResult.CANCELLED);
        AtomicInteger bookCalls = new AtomicInteger();
        when(courierClient.book(anyString(), any(CourierBookingRequest.class))).thenAnswer(invocation -> {
            if (bookCalls.incrementAndGet() == 1) {
                // 결제 후 예약이 택배사 응답을 기다리는 사이 주문 취소가 커밋됨
                inTransaction(() -> shipmentService.cancelShipment(7L));
            }
            return bookingResponse();
        });

        inTransaction(() -> shipmentService.createShipments(order()));

        assertThat(shipment()).satisfies(shipment -> {
            assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCELLED); // BOOKED로 덮어써지지 않음
            assertThat(shipment.getTrackingNo()).isEqualTo(TRACKING_NO);
            assertThat(shipment.getCourierCancelledAt()).isNotNull();
        });
        verify(courierClient).cancel(TRACKING_NO);
    }

    private void createBookedShipment() {
        when(memberQuery.getDefaultAddress(20L)).thenReturn(seller());
        when(courierClient.book(anyString(), any(CourierBookingRequest.class))).thenReturn(bookingResponse());
        when(courierClient.cancel(TRACKING_NO)).thenReturn(CourierCancelResult.CANCELLED);
        inTransaction(() -> shipmentService.createShipments(order()));
        assertThat(shipment().getStatus()).isEqualTo(ShipmentStatus.BOOKED);
    }

    private void inTransaction(Runnable task) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> task.run());
    }

    private Shipment shipment() {
        return shipmentRepository.findBySellerOrderId(7L).orElseThrow();
    }

    private MemberAddress seller() {
        return new MemberAddress(1L, "판매자", "01012345678", "12345", "서울", null);
    }

    private CourierBookingResponse bookingResponse() {
        return new CourierBookingResponse(TRACKING_NO, "shipment-20261016-000001", "RESERVED",
                OffsetDateTime.parse("2026-10-16T10:00:00+09:00"));
    }

    private OrderResult order() {
        SellerOrderResult sellerOrder = new SellerOrderResult(7L, 20L,
                List.of(new OrdersItemResult(11L, 101L, "몬스테라", new BigDecimal("10000"), OrderItemStatus.NORMAL)),
                new BigDecimal("10000"), null, new BigDecimal("3000"), null, null, null);
        return new OrderResult(9L, 10L, List.of(sellerOrder), new BigDecimal("13000"),
                "구매자", "01000000000", "12345", "부산", null, 1, OrderStatus.PAID);
    }
}

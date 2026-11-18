package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.common.exception.BusinessException;
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
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingRequest;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingResponse;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierClient;
import com.justcommit.backend.market.shipment.infrastructure.kafka.ShipmentStatusChangedEventPublisher;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
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
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 주문이 배송 취소를 요청하면 CANCEL_PENDING + 누른 시각만 기록하고 택배사는 부르지 않는지 실제 DB로 확인한다 (#490, ADR 0026).
// 택배사 취소는 택배 취소 큐가 보낸다.
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ShipmentService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 테스트가 직접 트랜잭션을 열고 닫는다
class ShipmentCancelIntegrationTest {

    private static final String TRACKING_NO = "404000000001";

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

    @BeforeEach
    void stubOrderAndSeller() {
        when(orderQuery.getShippingInfo(7L)).thenReturn(
                new OrderShippingInfo(9L, 10L, 20L, new BigDecimal("3000"), "구매자", "01000000000", "부산", null));
        when(memberQuery.getDefaultAddress(20L))
                .thenReturn(new MemberAddress(1L, "판매자", "01012345678", "12345", "서울", null));
    }

    @AfterEach
    void cleanUp() {
        shipmentRepository.deleteAll();
    }

    @Test
    @DisplayName("예약된 배송을 취소하면 CANCEL_PENDING + 누른 시각만 기록하고, 커밋 뒤에도 택배사를 부르지 않는다")
    void cancelBookedRecordsPendingOnly() {
        createBookedShipment();
        LocalDateTime before = LocalDateTime.now();

        inTransaction(() -> shipmentService.cancelShipment(7L));

        Shipment shipment = shipment();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCEL_PENDING);
        assertThat(shipment.getCancelRequestedAt()).isAfterOrEqualTo(before);
        assertThat(shipment.getNextAttemptAt()).isNull(); // 택배 취소 큐가 바로 집는다
        verify(courierClient, never()).cancelByCourierOrderNo(anyString(), any());
    }

    @Test
    @DisplayName("예약 전(READY) 배송을 취소해도 CANCEL_PENDING으로 기록한다 — 택배사에 예약이 있을 수 있어 큐가 확인한다")
    void cancelReadyRecordsPending() {
        inTransaction(() -> shipmentService.createShipments(order()));

        inTransaction(() -> shipmentService.cancelShipment(7L));

        assertThat(shipment().getStatus()).isEqualTo(ShipmentStatus.CANCEL_PENDING);
        verify(courierClient, never()).book(anyString(), any());
        verify(courierClient, never()).cancelByCourierOrderNo(anyString(), any());
    }

    @Test
    @DisplayName("주문 트랜잭션이 롤백되면 배송은 그대로다")
    void rollbackKeepsShipment() {
        createBookedShipment();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            shipmentService.cancelShipment(7L);
            status.setRollbackOnly(); // 환불 실패 등으로 주문 취소가 롤백되는 상황
        });

        assertThat(shipment().getStatus()).isEqualTo(ShipmentStatus.BOOKED);
        assertThat(shipment().getCancelRequestedAt()).isNull();
    }

    @Test
    @DisplayName("[S-E04] 같은 배송을 다시 취소하면 아무것도 바꾸지 않고 처음 누른 시각을 유지한다")
    void cancelTwiceKeepsFirstRequest() {
        createBookedShipment();
        inTransaction(() -> shipmentService.cancelShipment(7L));
        LocalDateTime first = shipment().getCancelRequestedAt();

        inTransaction(() -> shipmentService.cancelShipment(7L));

        assertThat(shipment().getStatus()).isEqualTo(ShipmentStatus.CANCEL_PENDING);
        assertThat(shipment().getCancelRequestedAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("같은 배송에 취소가 동시에 와도(따닥) 에러 없이 한 번만 기록된다")
    void concurrentCancelsRecordOnce() throws Exception {
        Long shipmentId = createBookedShipment();
        Long versionBefore = shipmentRepository.findById(shipmentId).orElseThrow().getVersion();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> runs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            runs.add(pool.submit(() -> {
                start.await();
                inTransaction(() -> shipmentService.cancelShipment(7L));
                return null;
            }));
        }
        start.countDown();
        for (Future<?> run : runs) {
            run.get(10, TimeUnit.SECONDS); // 어느 쪽도 예외(낙관적 락 충돌 등)로 실패하지 않음
        }
        pool.shutdown();

        Shipment shipment = shipmentRepository.findById(shipmentId).orElseThrow();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCEL_PENDING);
        assertThat(shipment.getVersion()).isEqualTo(versionBefore + 1); // 저장은 한 번
    }

    @Test
    @DisplayName("[S-E03] 기사가 2km 안(CANCEL_LOCKED)이거나 수거된 배송은 취소를 거부한다")
    void rejectsLockedOrPickedUp() {
        createBookedShipment();
        inTransaction(() -> {
            Shipment shipment = shipment();
            shipment.applyCourierStatus(ShipmentStatus.CANCEL_LOCKED, TRACKING_NO, LocalDateTime.now());
            shipmentRepository.save(shipment);
        });

        assertThatThrownBy(() -> inTransaction(() -> shipmentService.cancelShipment(7L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(OrderErrorCode.SHIPMENT_NOT_CANCELABLE));

        inTransaction(() -> {
            Shipment shipment = shipment();
            shipment.applyCourierStatus(ShipmentStatus.PICKED_UP, TRACKING_NO, LocalDateTime.now());
            shipmentRepository.save(shipment);
        });
        assertThatThrownBy(() -> inTransaction(() -> shipmentService.cancelShipment(7L)))
                .isInstanceOf(BusinessException.class);
        verify(courierClient, never()).cancelByCourierOrderNo(anyString(), any());
    }

    @Test
    @DisplayName("[S-F06] 예약 큐가 택배사를 기다리는 사이 취소가 먼저 저장되면 예약 결과가 취소를 덮어쓰지 않는다")
    void cancelWinsOverConcurrentBooking() {
        AtomicInteger bookCalls = new AtomicInteger();
        when(courierClient.book(anyString(), any(CourierBookingRequest.class))).thenAnswer(invocation -> {
            if (bookCalls.incrementAndGet() == 1) {
                // 예약 큐가 택배사 응답을 기다리는 사이 주문 취소가 커밋됨
                inTransaction(() -> shipmentService.cancelShipment(7L));
            }
            return bookingResponse();
        });
        inTransaction(() -> shipmentService.createShipments(order()));

        shipmentService.bookReadyShipments();

        // 택배사에 남은 예약은 택배 취소 큐가 같은 주문번호로 취소한다
        assertThat(shipment().getStatus()).isEqualTo(ShipmentStatus.CANCEL_PENDING);
    }

    private Long createBookedShipment() {
        when(courierClient.book(anyString(), any(CourierBookingRequest.class))).thenReturn(bookingResponse());
        inTransaction(() -> shipmentService.createShipments(order()));
        shipmentService.bookReadyShipments(); // 예약 큐
        assertThat(shipment().getStatus()).isEqualTo(ShipmentStatus.BOOKED);
        return shipment().getId();
    }

    private void inTransaction(Runnable task) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> task.run());
    }

    private Shipment shipment() {
        return shipmentRepository.findBySellerOrderId(7L).orElseThrow();
    }

    private CourierBookingResponse bookingResponse() {
        return new CourierBookingResponse(TRACKING_NO, "shipment-20261117-000001", "RESERVED",
                OffsetDateTime.parse("2026-11-17T10:00:00+09:00"));
    }

    private OrderResult order() {
        SellerOrderResult sellerOrder = new SellerOrderResult(7L, 20L,
                List.of(new OrdersItemResult(11L, 101L, "몬스테라", new BigDecimal("10000"), OrderItemStatus.NORMAL)),
                new BigDecimal("10000"), null, new BigDecimal("3000"), null, null, null);
        return new OrderResult(9L, 10L, List.of(sellerOrder), new BigDecimal("13000"),
                "구매자", "01000000000", "12345", "부산", null, 1, OrderStatus.PAID);
    }
}

package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.OrderShippingInfo;
import com.justcommit.backend.market.client.member.MemberQuery;
import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentCancelResult;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierCancelException;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierCancelResult;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierClient;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierShipmentView;
import com.justcommit.backend.market.shipment.infrastructure.kafka.ShipmentStatusChangedEventPublisher;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentCancelResultRepository;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 택배 취소 큐가 실제 DB(SKIP LOCKED · 임대)로 CANCEL_PENDING 배송을 택배사에 취소하고 결과를 남기는지 확인한다 (#490, ADR 0025).
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ShipmentService.class, ShipmentCancelQueueService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 큐가 직접 트랜잭션을 열고 닫는다
class ShipmentCancelQueueIntegrationTest {

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
    @Autowired ShipmentCancelQueueService cancelQueue;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired ShipmentCancelResultRepository cancelResultRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void stubOrderInfo() {
        when(orderQuery.getShippingInfo(anyLong())).thenReturn(
                new OrderShippingInfo(9L, 10L, 20L, new BigDecimal("3000"), "구매자", "01000000000", "부산", null));
    }

    @AfterEach
    void cleanUp() {
        cancelResultRepository.deleteAll();
        shipmentRepository.deleteAll();
    }

    @Test
    @DisplayName("택배사가 취소하면 배송 CANCELLED + 결과 행(취소됨) 하나를 남기고 상태 이벤트를 보낸다")
    void cancelledByCourier() {
        Shipment shipment = cancelPendingBooked(7L, "404000000007");
        when(courierClient.cancel("404000000007")).thenReturn(CourierCancelResult.CANCELLED);

        cancelQueue.cancelPendingShipments();

        assertThat(reload(shipment).getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(cancelResultRepository.findByShipmentId(shipment.getId())).hasValueSatisfying(result -> {
            assertThat(result.getResult()).isEqualTo(ShipmentCancelResult.Result.CANCELLED);
            assertThat(result.getSellerOrderId()).isEqualTo(7L);
            assertThat(result.getProcessedAt()).isNull(); // 주문 Saga가 아직 안 읽음
        });
        verify(statusChangedEventPublisher).publishAfterCommit(any(), any(), any());
    }

    @Test
    @DisplayName("택배사가 거절하면(409, 기사 2km 안 · 수거) 배송 CANCEL_LOCKED + 결과 행(취소 불가)")
    void lockedByCourier() {
        Shipment shipment = cancelPendingBooked(7L, "404000000007");
        when(courierClient.cancel("404000000007")).thenReturn(CourierCancelResult.NOT_CANCELLABLE);

        cancelQueue.cancelPendingShipments();

        assertThat(reload(shipment).getStatus()).isEqualTo(ShipmentStatus.CANCEL_LOCKED);
        assertThat(cancelResultRepository.findByShipmentId(shipment.getId()))
                .hasValueSatisfying(result -> assertThat(result.getResult()).isEqualTo(ShipmentCancelResult.Result.LOCKED));
    }

    @Test
    @DisplayName("택배사가 응답하지 않으면 CANCEL_PENDING을 유지하고 원인을 남기며, 10초 전에는 다시 부르지 않고 때가 되면 다시 보낸다")
    void retriesWhenCourierDoesNotAnswer() {
        Shipment shipment = cancelPendingBooked(7L, "404000000007");
        when(courierClient.cancel("404000000007"))
                .thenThrow(new CourierCancelException("타임아웃", new RuntimeException()))
                .thenReturn(CourierCancelResult.CANCELLED);
        LocalDateTime before = LocalDateTime.now();

        cancelQueue.cancelPendingShipments();
        cancelQueue.cancelPendingShipments(); // 바로 다시 돌아도 아직 때가 아님

        Shipment pending = reload(shipment);
        assertThat(pending.getStatus()).isEqualTo(ShipmentStatus.CANCEL_PENDING);
        assertThat(pending.getAttemptCount()).isEqualTo(1);
        assertThat(pending.getLastError()).startsWith("CourierCancelException");
        assertThat(pending.getNextAttemptAt()).isAfterOrEqualTo(before.plusSeconds(10));
        assertThat(cancelResultRepository.findByShipmentId(shipment.getId())).isEmpty();
        verify(courierClient, times(1)).cancel(anyString());

        makeDue(shipment.getId());
        cancelQueue.cancelPendingShipments();

        assertThat(reload(shipment).getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(reload(shipment).getLastError()).isNull();
        verify(courierClient, times(2)).cancel("404000000007");
    }

    @Test
    @DisplayName("송장번호가 없는(예약 전 취소) 배송은 주문번호로 찾아서, 택배사에 예약이 없으면 택배사 취소 없이 끝낸다")
    void readyWithoutReservation() {
        Shipment shipment = cancelPendingReady(7L);
        when(courierClient.findByCourierOrderNo(shipment.getCourierOrderNo())).thenReturn(Optional.empty());

        cancelQueue.cancelPendingShipments();

        assertThat(reload(shipment).getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        verify(courierClient, never()).cancel(anyString());
        assertThat(cancelResultRepository.findByShipmentId(shipment.getId())).isPresent();
    }

    @Test
    @DisplayName("송장번호가 없지만 택배사에 예약이 있으면(예약 응답 유실) 찾은 송장번호로 취소한다")
    void readyWithLostReservation() {
        Shipment shipment = cancelPendingReady(7L);
        when(courierClient.findByCourierOrderNo(shipment.getCourierOrderNo()))
                .thenReturn(Optional.of(new CourierShipmentView("404000000099", shipment.getCourierOrderNo(), "RESERVED")));
        when(courierClient.cancel("404000000099")).thenReturn(CourierCancelResult.CANCELLED);

        cancelQueue.cancelPendingShipments();

        verify(courierClient).cancel("404000000099");
        assertThat(reload(shipment).getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("한 인스턴스가 택배사를 기다리는 동안 다른 인스턴스가 취소 큐를 돌아도 같은 배송을 다시 집지 않는다")
    void otherInstanceSkipsShipmentBeingCancelled() throws Exception {
        List<Shipment> shipments = List.of(
                cancelPendingBooked(100L, "404000000100"),
                cancelPendingBooked(101L, "404000000101"),
                cancelPendingBooked(102L, "404000000102"));
        CountDownLatch podAInCourier = new CountDownLatch(1);
        CountDownLatch releasePodA = new CountDownLatch(1);
        AtomicBoolean firstCall = new AtomicBoolean(true);
        when(courierClient.cancel(anyString())).thenAnswer(inv -> {
            if (firstCall.compareAndSet(true, false)) {
                podAInCourier.countDown();
                releasePodA.await(10, TimeUnit.SECONDS); // 인스턴스 A는 첫 배송에서 택배사 응답을 기다리는 중
            }
            return CourierCancelResult.CANCELLED;
        });

        ExecutorService podA = Executors.newSingleThreadExecutor();
        Future<?> runA = podA.submit(() -> cancelQueue.cancelPendingShipments());
        assertThat(podAInCourier.await(10, TimeUnit.SECONDS)).isTrue();

        cancelQueue.cancelPendingShipments(); // 그사이 인스턴스 B가 큐를 한 번 다 돈다
        releasePodA.countDown();
        runA.get(10, TimeUnit.SECONDS);
        podA.shutdown();

        verify(courierClient, times(shipments.size())).cancel(anyString());
        for (Shipment shipment : shipments) {
            verify(courierClient, times(1)).cancel(eq(shipment.getTrackingNo()));
            assertThat(reload(shipment).getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        }
    }

    private Shipment cancelPendingBooked(long sellerOrderId, String trackingNo) {
        Shipment shipment = savedShipment(sellerOrderId);
        inTransaction(() -> {
            Shipment s = shipmentRepository.findById(shipment.getId()).orElseThrow();
            s.book(trackingNo, LocalDateTime.of(2026, 11, 17, 9, 0));
            shipmentRepository.save(s);
        });
        inTransaction(() -> shipmentService.cancelShipment(sellerOrderId));
        return reload(shipment);
    }

    private Shipment cancelPendingReady(long sellerOrderId) {
        Shipment shipment = savedShipment(sellerOrderId);
        inTransaction(() -> shipmentService.cancelShipment(sellerOrderId));
        return reload(shipment);
    }

    private Shipment savedShipment(long sellerOrderId) {
        Shipment shipment = shipmentRepository.save(new Shipment(sellerOrderId, "몬스테라"));
        shipment.assignCourierOrderNo(LocalDate.of(2026, 11, 17));
        return shipmentRepository.save(shipment);
    }

    private Shipment reload(Shipment shipment) {
        return shipmentRepository.findById(shipment.getId()).orElseThrow();
    }

    private void makeDue(Long shipmentId) {
        jdbcTemplate.update("update shipment set next_attempt_at = now() - interval '1 second' where id = ?", shipmentId);
    }

    private void inTransaction(Runnable task) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> task.run());
    }
}

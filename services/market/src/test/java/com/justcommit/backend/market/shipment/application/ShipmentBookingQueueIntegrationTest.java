package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.OrderShippingInfo;
import com.justcommit.backend.market.client.member.MemberAddress;
import com.justcommit.backend.market.client.member.MemberQuery;
import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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

// 택배 예약 큐가 실제 DB(SKIP LOCKED · 임대)로 READY 배송을 예약 · 재시도하는지 확인한다.
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ShipmentService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 큐가 직접 트랜잭션을 열고 닫는다
class ShipmentBookingQueueIntegrationTest {

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
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void stubOrderAndSeller() {
        when(orderQuery.getShippingInfo(anyLong())).thenReturn(
                new OrderShippingInfo(9L, 10L, 20L, new BigDecimal("3000"), "구매자", "01000000000", "부산", null));
        when(memberQuery.getDefaultAddress(20L))
                .thenReturn(new MemberAddress(1L, "판매자", "01012345678", "12345", "서울", null));
    }

    @AfterEach
    void cleanUp() {
        shipmentRepository.deleteAll();
    }

    @Test
    @DisplayName("큐가 READY 배송을 택배사에 예약하고 BOOKED · 송장번호를 저장한다")
    void booksReadyShipment() {
        Shipment saved = readyShipment(7L);
        courierSucceeds();

        shipmentService.bookReadyShipments();

        verify(courierClient).book(eq(saved.getCourierOrderNo()), any(CourierBookingRequest.class)); // 같은 멱등키
        Shipment shipment = shipmentRepository.findById(saved.getId()).orElseThrow();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.BOOKED);
        assertThat(shipment.getTrackingNo()).isEqualTo("404" + saved.getCourierOrderNo().substring(18));
        verify(statusChangedEventPublisher).publishAfterCommit(any(), any(), any()); // 판매자에게 택배 예약 알림
    }

    @Test
    @DisplayName("택배사가 실패하면 READY로 남고 10초 뒤까지는 다시 부르지 않는다")
    void failureWaitsBeforeRetry() {
        Shipment saved = readyShipment(7L);
        when(courierClient.book(anyString(), any(CourierBookingRequest.class)))
                .thenThrow(new CourierBookingException("타임아웃", new RuntimeException(), true));
        LocalDateTime before = LocalDateTime.now();

        shipmentService.bookReadyShipments();
        shipmentService.bookReadyShipments(); // 바로 다시 돌아도 아직 시도할 때가 아님

        verify(courierClient, times(1)).book(anyString(), any());
        Shipment shipment = shipmentRepository.findById(saved.getId()).orElseThrow();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.READY);
        assertThat(shipment.getAttemptCount()).isEqualTo(1);
        assertThat(shipment.getNextAttemptAt()).isAfterOrEqualTo(before.plusSeconds(10));
    }

    @Test
    @DisplayName("다음 시도 시각이 지나면 같은 멱등키로 다시 예약하고, 실패가 이어지면 간격이 30초로 늘어난다")
    void retriesWhenDueAndBacksOff() {
        Shipment saved = readyShipment(7L);
        when(courierClient.book(anyString(), any(CourierBookingRequest.class)))
                .thenThrow(new CourierBookingException("타임아웃", new RuntimeException(), true));
        shipmentService.bookReadyShipments();
        makeDue(saved.getId());
        LocalDateTime before = LocalDateTime.now();

        shipmentService.bookReadyShipments();

        verify(courierClient, times(2)).book(eq(saved.getCourierOrderNo()), any());
        Shipment shipment = shipmentRepository.findById(saved.getId()).orElseThrow();
        assertThat(shipment.getAttemptCount()).isEqualTo(2);
        assertThat(shipment.getNextAttemptAt()).isAfterOrEqualTo(before.plusSeconds(30));
    }

    @Test
    @DisplayName("판매자 주소를 못 읽어도 READY로 남기고 나중에 다시 시도한다")
    void sellerAddressFailureRetriesLater() {
        Shipment saved = readyShipment(7L);
        when(memberQuery.getDefaultAddress(20L)).thenThrow(new IllegalStateException("기본 배송지 없음"));

        shipmentService.bookReadyShipments();

        verify(courierClient, never()).book(anyString(), any());
        Shipment shipment = shipmentRepository.findById(saved.getId()).orElseThrow();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.READY);
        assertThat(shipment.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("한 배송이 실패해도 다른 배송은 이어서 예약한다")
    void oneFailureDoesNotBlockOthers() {
        Shipment failing = readyShipment(7L);
        Shipment ok = readyShipment(8L);
        when(courierClient.book(eq(failing.getCourierOrderNo()), any()))
                .thenThrow(new CourierBookingException("400", new RuntimeException(), false));
        when(courierClient.book(eq(ok.getCourierOrderNo()), any())).thenAnswer(inv -> booked(inv.getArgument(0)));

        shipmentService.bookReadyShipments();

        assertThat(shipmentRepository.findById(failing.getId()).orElseThrow().getStatus()).isEqualTo(ShipmentStatus.READY);
        assertThat(shipmentRepository.findById(ok.getId()).orElseThrow().getStatus()).isEqualTo(ShipmentStatus.BOOKED);
    }

    @Test
    @DisplayName("한 인스턴스가 택배사를 기다리는 동안 다른 인스턴스가 큐를 돌아도 같은 배송을 다시 집지 않는다")
    void otherInstanceSkipsShipmentBeingBooked() throws Exception {
        List<Shipment> shipments = List.of(readyShipment(100L), readyShipment(101L), readyShipment(102L));
        CountDownLatch podAInCourier = new CountDownLatch(1);
        CountDownLatch releasePodA = new CountDownLatch(1);
        AtomicBoolean firstCall = new AtomicBoolean(true);
        when(courierClient.book(anyString(), any(CourierBookingRequest.class))).thenAnswer(inv -> {
            if (firstCall.compareAndSet(true, false)) {
                podAInCourier.countDown();
                releasePodA.await(10, TimeUnit.SECONDS); // 인스턴스 A는 첫 배송에서 택배사 응답을 기다리는 중
            }
            return booked(inv.getArgument(0));
        });

        ExecutorService podA = Executors.newSingleThreadExecutor();
        Future<?> runA = podA.submit(() -> shipmentService.bookReadyShipments());
        assertThat(podAInCourier.await(10, TimeUnit.SECONDS)).isTrue();

        shipmentService.bookReadyShipments(); // 그사이 인스턴스 B가 큐를 한 번 다 돈다
        releasePodA.countDown();
        runA.get(10, TimeUnit.SECONDS); // A가 예외로 멈췄으면 여기서 실패
        podA.shutdown();

        verify(courierClient, times(shipments.size())).book(anyString(), any()); // 전체 호출 수 = 배송 수

        for (Shipment shipment : shipments) {
            verify(courierClient, times(1)).book(eq(shipment.getCourierOrderNo()), any());
            assertThat(shipmentRepository.findById(shipment.getId()).orElseThrow().getStatus())
                    .isEqualTo(ShipmentStatus.BOOKED);
        }
    }

    private Shipment readyShipment(long sellerOrderId) {
        Shipment shipment = shipmentRepository.save(new Shipment(sellerOrderId, "몬스테라"));
        shipment.assignCourierOrderNo(LocalDate.of(2026, 11, 16));
        return shipmentRepository.save(shipment);
    }

    private void courierSucceeds() {
        when(courierClient.book(anyString(), any(CourierBookingRequest.class)))
                .thenAnswer(inv -> booked(inv.getArgument(0)));
    }

    // 택배사 응답: courierOrderNo 끝 6자리로 송장번호를 만든다
    private CourierBookingResponse booked(String courierOrderNo) {
        return new CourierBookingResponse("404" + courierOrderNo.substring(18), courierOrderNo, "RESERVED",
                OffsetDateTime.parse("2026-11-16T10:00:00+09:00"));
    }

    private void makeDue(Long shipmentId) {
        jdbcTemplate.update("update shipment set next_attempt_at = now() - interval '1 second' where id = ?", shipmentId);
    }
}

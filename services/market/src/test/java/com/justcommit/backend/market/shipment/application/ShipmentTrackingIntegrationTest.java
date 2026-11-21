package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.market.ShipmentTracking;
import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierClient;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierShipmentView;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ShipmentTrackingService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 서비스가 트랜잭션 밖에서 택배사를 부르는지 보려고 테스트 트랜잭션을 끈다
class ShipmentTrackingIntegrationTest {

    private static final LocalDateTime BOOKED_AT = LocalDateTime.of(2026, 11, 20, 10, 0);

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @MockitoBean CourierClient courierClient;

    @Autowired ShipmentTrackingService trackingService;
    @Autowired ShipmentRepository shipmentRepository;

    @AfterEach
    void cleanUp() {
        shipmentRepository.deleteAll();
    }

    @Test
    @DisplayName("예약된 배송은 DB 트랜잭션 밖에서 택배사를 불러 남은 거리 · 지점 · 세부 단계를 보탠다")
    void bookedShipmentAddsCourierViewOutsideTransaction() {
        Shipment shipment = bookedShipment(101L);
        AtomicBoolean calledInTransaction = new AtomicBoolean(true);
        when(courierClient.track(shipment.getCourierOrderNo())).thenAnswer(invocation -> {
            calledInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.of(new CourierShipmentView("404000000001", shipment.getCourierOrderNo(),
                    "NEAR_5KM", 4321, "강남우체국"));
        });

        ShipmentTracking tracking = trackingService.track(101L).orElseThrow();

        assertThat(calledInTransaction).isFalse();
        assertThat(tracking.status()).isEqualTo("BOOKED");
        assertThat(tracking.trackingNo()).isEqualTo("404000000001");
        assertThat(tracking.bookedAt()).isEqualTo(BOOKED_AT);
        assertThat(tracking.courierStatus()).isEqualTo("NEAR_5KM");
        assertThat(tracking.remainingDistanceMeters()).isEqualTo(4321);
        assertThat(tracking.locationName()).isEqualTo("강남우체국");
        assertThat(tracking.checkedAt()).isNotNull();
    }

    @Test
    @DisplayName("택배사 조회가 실패하면 거리 · 지점은 비우고 market이 가진 단계 · 시각만 돌려준다")
    void courierFailureLeavesCourierFieldsEmpty() {
        bookedShipment(102L);
        when(courierClient.track(anyString())).thenReturn(Optional.empty());

        ShipmentTracking tracking = trackingService.track(102L).orElseThrow();

        assertThat(tracking.status()).isEqualTo("BOOKED");
        assertThat(tracking.bookedAt()).isEqualTo(BOOKED_AT);
        assertThat(tracking.courierStatus()).isNull();
        assertThat(tracking.remainingDistanceMeters()).isNull();
        assertThat(tracking.locationName()).isNull();
        assertThat(tracking.checkedAt()).isNull();
    }

    @Test
    @DisplayName("배송 완료 · 예약 전 배송은 택배사를 부르지 않는다")
    void finishedOrNotBookedShipmentSkipsCourier() {
        Shipment delivered = bookedShipment(103L);
        delivered.applyCourierStatus(ShipmentStatus.DELIVERED, "404000000001", BOOKED_AT.plusHours(3));
        shipmentRepository.save(delivered);
        shipmentRepository.save(new Shipment(104L, "몬스테라")); // READY

        assertThat(trackingService.track(103L).orElseThrow().deliveredAt()).isEqualTo(BOOKED_AT.plusHours(3));
        assertThat(trackingService.track(104L).orElseThrow().status()).isEqualTo("READY");
        verify(courierClient, never()).track(anyString());
    }

    @Test
    @DisplayName("배송이 아직 없으면(판매자 수락 전) 빈 값")
    void noShipmentIsEmpty() {
        assertThat(trackingService.track(999L)).isEmpty();
    }

    // ---- 반품 택배 (#617) ----

    @Test
    @DisplayName("반품 배송은 배송 id로 찾아 DB 트랜잭션 밖에서 택배사를 불러 기사님 거리 · 지점을 보탠다")
    void returnShipmentAddsCourierViewOutsideTransaction() {
        Shipment shipment = book(Shipment.returnOf(201L, "몬스테라"));
        AtomicBoolean calledInTransaction = new AtomicBoolean(true);
        when(courierClient.track(shipment.getCourierOrderNo())).thenAnswer(invocation -> {
            calledInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.of(new CourierShipmentView("404000000001", shipment.getCourierOrderNo(),
                    "NEAR_2KM", 1500, "서초우체국"));
        });

        ShipmentTracking tracking = trackingService.trackReturn(shipment.getId()).orElseThrow();

        assertThat(calledInTransaction).isFalse();
        assertThat(tracking.status()).isEqualTo("BOOKED");
        assertThat(tracking.bookedAt()).isEqualTo(BOOKED_AT);
        assertThat(tracking.courierStatus()).isEqualTo("NEAR_2KM");
        assertThat(tracking.remainingDistanceMeters()).isEqualTo(1500);
        assertThat(tracking.locationName()).isEqualTo("서초우체국");
        assertThat(tracking.checkedAt()).isNotNull();
    }

    @Test
    @DisplayName("반품 배송도 택배사 조회가 실패하면 거리 · 지점만 비운다")
    void returnShipmentCourierFailureLeavesCourierFieldsEmpty() {
        Shipment shipment = book(Shipment.returnOf(202L, "몬스테라"));
        when(courierClient.track(anyString())).thenReturn(Optional.empty());

        ShipmentTracking tracking = trackingService.trackReturn(shipment.getId()).orElseThrow();

        assertThat(tracking.status()).isEqualTo("BOOKED");
        assertThat(tracking.remainingDistanceMeters()).isNull();
        assertThat(tracking.locationName()).isNull();
        assertThat(tracking.checkedAt()).isNull();
    }

    @Test
    @DisplayName("반품 조회에 가는 배송 id나 없는 id를 주면 빈 값이고 택배사를 부르지 않는다")
    void trackReturnIgnoresOutboundOrMissingShipment() {
        Shipment outbound = bookedShipment(203L);

        assertThat(trackingService.trackReturn(outbound.getId())).isEmpty();
        assertThat(trackingService.trackReturn(999_999L)).isEmpty();
        verify(courierClient, never()).track(anyString());
    }

    private Shipment bookedShipment(Long sellerOrderId) {
        return book(new Shipment(sellerOrderId, "몬스테라"));
    }

    private Shipment book(Shipment newShipment) {
        Shipment shipment = shipmentRepository.save(newShipment);
        shipment.assignCourierOrderNo(LocalDate.of(2026, 11, 20));
        shipment.book("404000000001", BOOKED_AT);
        return shipmentRepository.save(shipment);
    }
}

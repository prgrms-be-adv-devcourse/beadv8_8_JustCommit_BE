package com.justcommit.backend.market.shipment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShipmentTest {

    private static final Long SELLER_ORDER_ID = 10L;

    @Test
    @DisplayName("새로 만든 배송은 READY 상태이고 송장번호가 없다")
    void newShipmentIsReady() {
        Shipment shipment = new Shipment(SELLER_ORDER_ID);

        assertThat(shipment.getSellerOrderId()).isEqualTo(SELLER_ORDER_ID);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.READY);
        assertThat(shipment.getTrackingNo()).isNull();
    }

    @Test
    @DisplayName("READY 상태에서 예약하면 BOOKED가 되고 송장번호와 예약 시각이 저장된다")
    void bookFromReady() {
        Shipment shipment = new Shipment(SELLER_ORDER_ID);
        LocalDateTime bookedAt = LocalDateTime.of(2026, 10, 12, 10, 0);

        shipment.book("404000000001", bookedAt);

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.BOOKED);
        assertThat(shipment.getTrackingNo()).isEqualTo("404000000001");
        assertThat(shipment.getBookedAt()).isEqualTo(bookedAt);
    }

    @Test
    @DisplayName("이미 예약된 배송을 다시 예약하면 예외가 나고 처음 송장번호가 유지된다")
    void bookTwiceFails() {
        Shipment shipment = new Shipment(SELLER_ORDER_ID);
        shipment.book("404000000001", LocalDateTime.of(2026, 10, 12, 10, 0));

        assertThatThrownBy(() -> shipment.book("404000000002", LocalDateTime.of(2026, 10, 12, 11, 0)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(shipment.getTrackingNo()).isEqualTo("404000000001");
    }

    @Test
    @DisplayName("택배 주문번호는 shipment-날짜-배송ID 6자리 형식으로 만든다")
    void assignCourierOrderNo() {
        Shipment shipment = new Shipment(SELLER_ORDER_ID);
        ReflectionTestUtils.setField(shipment, "id", 45L); // DB 저장 없이 id만 넣기

        shipment.assignCourierOrderNo(LocalDate.of(2026, 10, 12));

        assertThat(shipment.getCourierOrderNo()).isEqualTo("shipment-20261012-000045");
    }

    @Test
    @DisplayName("택배 주문번호가 이미 있으면 다시 만들 수 없다")
    void assignCourierOrderNoTwiceFails() {
        Shipment shipment = new Shipment(SELLER_ORDER_ID);
        ReflectionTestUtils.setField(shipment, "id", 45L);
        shipment.assignCourierOrderNo(LocalDate.of(2026, 10, 12));

        assertThatThrownBy(() -> shipment.assignCourierOrderNo(LocalDate.of(2026, 10, 13)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(shipment.getCourierOrderNo()).isEqualTo("shipment-20261012-000045");
    }
}

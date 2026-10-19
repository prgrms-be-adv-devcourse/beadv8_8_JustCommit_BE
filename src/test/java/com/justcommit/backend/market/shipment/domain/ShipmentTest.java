package com.justcommit.backend.market.shipment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShipmentTest {

    private static final Long SELLER_ORDER_ID = 10L;

    @Test
    @DisplayName("새로 만든 배송은 READY 상태이고 송장번호가 없다")
    void newShipmentIsReady() {
        Shipment shipment = newShipment();

        assertThat(shipment.getSellerOrderId()).isEqualTo(SELLER_ORDER_ID);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.READY);
        assertThat(shipment.getTrackingNo()).isNull();
    }

    @Test
    @DisplayName("READY 상태에서 예약하면 BOOKED가 되고 송장번호와 예약 시각이 저장된다")
    void bookFromReady() {
        Shipment shipment = newShipment();
        LocalDateTime bookedAt = LocalDateTime.of(2026, 10, 12, 10, 0);

        shipment.book("404000000001", bookedAt);

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.BOOKED);
        assertThat(shipment.getTrackingNo()).isEqualTo("404000000001");
        assertThat(shipment.getBookedAt()).isEqualTo(bookedAt);
    }

    @Test
    @DisplayName("이미 예약된 배송을 다시 예약하면 예외가 나고 처음 송장번호가 유지된다")
    void bookTwiceFails() {
        Shipment shipment = newShipment();
        shipment.book("404000000001", LocalDateTime.of(2026, 10, 12, 10, 0));

        assertThatThrownBy(() -> shipment.book("404000000002", LocalDateTime.of(2026, 10, 12, 11, 0)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(shipment.getTrackingNo()).isEqualTo("404000000001");
    }

    @Test
    @DisplayName("택배 주문번호는 shipment-날짜-배송ID 6자리 형식으로 만든다")
    void assignCourierOrderNo() {
        Shipment shipment = newShipment();
        ReflectionTestUtils.setField(shipment, "id", 45L); // DB 저장 없이 id만 넣기

        shipment.assignCourierOrderNo(LocalDate.of(2026, 10, 12));

        assertThat(shipment.getCourierOrderNo()).isEqualTo("shipment-20261012-000045");
    }

    @Test
    @DisplayName("택배 주문번호가 이미 있으면 다시 만들 수 없다")
    void assignCourierOrderNoTwiceFails() {
        Shipment shipment = newShipment();
        ReflectionTestUtils.setField(shipment, "id", 45L);
        shipment.assignCourierOrderNo(LocalDate.of(2026, 10, 12));

        assertThatThrownBy(() -> shipment.assignCourierOrderNo(LocalDate.of(2026, 10, 13)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(shipment.getCourierOrderNo()).isEqualTo("shipment-20261012-000045");
    }

    @Test
    @DisplayName("택배사 상태가 앞으로 가면 반영하고, 수거·출발·완료 시각을 각각 저장한다")
    void applyCourierStatusForward() {
        Shipment shipment = bookedShipment();
        LocalDateTime pickedUpAt = LocalDateTime.of(2026, 10, 12, 10, 3);
        LocalDateTime inTransitAt = LocalDateTime.of(2026, 10, 12, 10, 5);
        LocalDateTime deliveredAt = LocalDateTime.of(2026, 10, 12, 15, 0);

        boolean pickedUp = shipment.applyCourierStatus(ShipmentStatus.PICKED_UP, "404000000001", pickedUpAt);
        boolean inTransit = shipment.applyCourierStatus(ShipmentStatus.IN_TRANSIT, "404000000001", inTransitAt);
        boolean delivered = shipment.applyCourierStatus(ShipmentStatus.DELIVERED, "404000000001", deliveredAt);

        assertThat(pickedUp).isTrue();
        assertThat(inTransit).isTrue();
        assertThat(delivered).isTrue();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(shipment.getPickedUpAt()).isEqualTo(pickedUpAt);
        assertThat(shipment.getInTransitAt()).isEqualTo(inTransitAt);
        assertThat(shipment.getDeliveredAt()).isEqualTo(deliveredAt);
    }

    @Test
    @DisplayName("같은 상태가 다시 오면 무시한다")
    void applyCourierStatusDuplicateIgnored() {
        Shipment shipment = bookedShipment();
        shipment.applyCourierStatus(ShipmentStatus.PICKED_UP, "404000000001", LocalDateTime.of(2026, 10, 12, 10, 3));

        boolean applied = shipment.applyCourierStatus(ShipmentStatus.PICKED_UP, "404000000001", LocalDateTime.of(2026, 10, 12, 10, 3));

        assertThat(applied).isFalse();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.PICKED_UP);
    }

    @Test
    @DisplayName("뒤로 가는 상태가 늦게 도착하면 무시한다")
    void applyCourierStatusOutOfOrderIgnored() {
        Shipment shipment = bookedShipment();
        shipment.applyCourierStatus(ShipmentStatus.IN_TRANSIT, "404000000001", LocalDateTime.of(2026, 10, 12, 10, 5));

        boolean applied = shipment.applyCourierStatus(ShipmentStatus.PICKED_UP, "404000000001", LocalDateTime.of(2026, 10, 12, 10, 3));

        assertThat(applied).isFalse();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
    }

    @Test
    @DisplayName("예약 응답을 놓쳐 송장번호가 없으면 웹훅의 송장번호로 채운다")
    void applyCourierStatusFillsMissingTrackingNo() {
        Shipment shipment = newShipment(); // READY, 송장번호 없음

        boolean applied = shipment.applyCourierStatus(ShipmentStatus.PICKED_UP, "404000000001", LocalDateTime.of(2026, 10, 12, 10, 3));

        assertThat(applied).isTrue();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.PICKED_UP);
        assertThat(shipment.getTrackingNo()).isEqualTo("404000000001");
    }

    @Test
    @DisplayName("예약된 배송을 취소하면 CANCELLED가 되고 취소 시각이 저장된다")
    void cancelBooked() {
        Shipment shipment = bookedShipment();
        LocalDateTime cancelledAt = LocalDateTime.of(2026, 10, 12, 10, 1);

        boolean cancelled = shipment.cancel(cancelledAt);

        assertThat(cancelled).isTrue();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(shipment.getCancelledAt()).isEqualTo(cancelledAt);
    }

    @Test
    @DisplayName("이미 취소된 배송을 다시 취소하면 아무것도 바꾸지 않는다")
    void cancelTwiceIgnored() {
        Shipment shipment = newShipment();
        LocalDateTime firstCancelledAt = LocalDateTime.of(2026, 10, 12, 10, 1);
        shipment.cancel(firstCancelledAt);

        boolean cancelled = shipment.cancel(LocalDateTime.of(2026, 10, 12, 11, 0));

        assertThat(cancelled).isFalse();
        assertThat(shipment.getCancelledAt()).isEqualTo(firstCancelledAt);
    }

    @Test
    @DisplayName("수거된 배송은 취소할 수 없다")
    void cancelPickedUpFails() {
        Shipment shipment = bookedShipment();
        shipment.applyCourierStatus(ShipmentStatus.PICKED_UP, "404000000001", LocalDateTime.of(2026, 10, 12, 10, 3));

        assertThatThrownBy(() -> shipment.cancel(LocalDateTime.of(2026, 10, 12, 10, 5)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.PICKED_UP);
    }

    @Test
    @DisplayName("취소 후 웹훅이 와도 상태가 바뀌지 않는다")
    void webhookAfterCancelIgnored() {
        Shipment shipment = bookedShipment();
        shipment.cancel(LocalDateTime.of(2026, 10, 12, 10, 1));

        boolean applied = shipment.applyCourierStatus(ShipmentStatus.PICKED_UP, "404000000001", LocalDateTime.of(2026, 10, 12, 10, 3));

        assertThat(applied).isFalse();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("READY에서 취소한 배송은 택배사 취소를 기록할 때 받아 온 송장번호로 채운다")
    void markCourierCancelledFillsTrackingNo() {
        Shipment shipment = newShipment();
        shipment.cancel(LocalDateTime.of(2026, 10, 12, 10, 1));
        LocalDateTime courierCancelledAt = LocalDateTime.of(2026, 10, 12, 10, 2);

        shipment.markCourierCancelled("404000000001", courierCancelledAt);

        assertThat(shipment.getTrackingNo()).isEqualTo("404000000001");
        assertThat(shipment.getCourierCancelledAt()).isEqualTo(courierCancelledAt);
    }

    private Shipment newShipment() {
        return new Shipment(9L, SELLER_ORDER_ID, 20L, 10L, "구매자", "01000000000", "부산", "몬스테라", new BigDecimal("3000"));
    }

    private Shipment bookedShipment() {
        Shipment shipment = newShipment();
        shipment.book("404000000001", LocalDateTime.of(2026, 10, 12, 10, 0));
        return shipment;
    }
}

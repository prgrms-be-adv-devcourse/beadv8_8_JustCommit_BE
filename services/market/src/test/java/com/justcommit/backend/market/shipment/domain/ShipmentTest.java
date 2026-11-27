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
    @DisplayName("기존 생성자로 만든 배송은 가는 길이다")
    void constructorCreatesOutbound() {
        Shipment shipment = newShipment();

        assertThat(shipment.getDirection()).isEqualTo(ShipmentDirection.OUTBOUND);
        assertThat(shipment.isReturn()).isFalse();
    }

    @Test
    @DisplayName("반품 배송은 방향이 반품이고 READY 상태로 만들어진다 (#383)")
    void returnOfCreatesReadyReturnShipment() {
        Shipment shipment = Shipment.returnOf(SELLER_ORDER_ID, "몬스테라");

        assertThat(shipment.getSellerOrderId()).isEqualTo(SELLER_ORDER_ID);
        assertThat(shipment.getDirection()).isEqualTo(ShipmentDirection.RETURN);
        assertThat(shipment.isReturn()).isTrue();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.READY);
        assertThat(shipment.getItemSummary()).isEqualTo("몬스테라");
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
    @DisplayName("[S-F03] 같은 상태가 다시 오면 무시한다")
    void applyCourierStatusDuplicateIgnored() {
        Shipment shipment = bookedShipment();
        shipment.applyCourierStatus(ShipmentStatus.PICKED_UP, "404000000001", LocalDateTime.of(2026, 10, 12, 10, 3));

        boolean applied = shipment.applyCourierStatus(ShipmentStatus.PICKED_UP, "404000000001", LocalDateTime.of(2026, 10, 12, 10, 3));

        assertThat(applied).isFalse();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.PICKED_UP);
    }

    @Test
    @DisplayName("[S-F03] 뒤로 가는 상태가 늦게 도착하면 무시한다")
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
    @DisplayName("취소 후 웹훅이 와도 상태가 바뀌지 않는다")
    void webhookAfterCancelIgnored() {
        Shipment shipment = bookedShipment();
        shipment.requestCancel(LocalDateTime.of(2026, 10, 12, 10, 1));
        shipment.completeCancel(LocalDateTime.of(2026, 10, 12, 10, 2));

        boolean applied = shipment.applyCourierStatus(ShipmentStatus.PICKED_UP, "404000000001", LocalDateTime.of(2026, 10, 12, 10, 3));

        assertThat(applied).isFalse();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("새 배송은 예약 큐가 바로 집을 수 있다 (다음 시도 시각 없음, 실패 0회)")
    void newShipmentIsReadyForQueueImmediately() {
        Shipment shipment = newShipment();

        assertThat(shipment.getNextAttemptAt()).isNull();
        assertThat(shipment.getAttemptCount()).isZero();
    }

    @Test
    @DisplayName("예약 큐가 집으면 다음 시도 시각을 30초 뒤로 미뤄 다른 파드가 다시 집지 않게 한다")
    void leasePushesNextAttempt() {
        Shipment shipment = newShipment();
        LocalDateTime now = LocalDateTime.of(2026, 11, 16, 10, 0, 0);

        shipment.lease(now);

        assertThat(shipment.getNextAttemptAt()).isEqualTo(now.plusSeconds(30));
        assertThat(shipment.getAttemptCount()).isZero();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.READY);
    }

    @Test
    @DisplayName("[S-F02] 택배 예약에 실패하면 READY로 남고 다음 시도까지 10초 → 30초 → 1분, 이후 1분 고정으로 기다린다")
    void bookingFailureBacksOff() {
        Shipment shipment = newShipment();
        LocalDateTime now = LocalDateTime.of(2026, 11, 16, 10, 0, 0);

        shipment.recordAttemptFailure(now, "TIMEOUT");
        assertThat(shipment.getNextAttemptAt()).isEqualTo(now.plusSeconds(10));

        shipment.recordAttemptFailure(now, "TIMEOUT");
        assertThat(shipment.getNextAttemptAt()).isEqualTo(now.plusSeconds(30));

        shipment.recordAttemptFailure(now, "TIMEOUT");
        assertThat(shipment.getNextAttemptAt()).isEqualTo(now.plusMinutes(1));

        shipment.recordAttemptFailure(now, "TIMEOUT");
        assertThat(shipment.getNextAttemptAt()).isEqualTo(now.plusMinutes(1));

        assertThat(shipment.getAttemptCount()).isEqualTo(4);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.READY);
        assertThat(shipment.getLastError()).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("실패 원인은 500자까지만 남긴다")
    void lastErrorIsTruncated() {
        Shipment shipment = newShipment();

        shipment.recordAttemptFailure(LocalDateTime.of(2026, 11, 17, 10, 0), "x".repeat(600));

        assertThat(shipment.getLastError()).hasSize(500);
    }

    @Test
    @DisplayName("예약에 성공하면 예약 큐의 시도 기록(다음 시도 시각 · 횟수 · 실패 원인)을 비운다")
    void bookResetsQueue() {
        Shipment shipment = newShipment();
        shipment.recordAttemptFailure(LocalDateTime.of(2026, 11, 17, 10, 0), "TIMEOUT");

        shipment.book("404000000001", LocalDateTime.of(2026, 11, 17, 10, 1));

        assertThat(shipment.getNextAttemptAt()).isNull();
        assertThat(shipment.getAttemptCount()).isZero();
        assertThat(shipment.getLastError()).isNull();
    }

    @Test
    @DisplayName("READY · BOOKED에서 취소를 누르면 CANCEL_PENDING + 누른 시각만 기록하고, 취소 큐가 바로 집게 시도 기록을 비운다")
    void requestCancelRecordsPending() {
        LocalDateTime requestedAt = LocalDateTime.of(2026, 11, 17, 10, 0);
        Shipment ready = newShipment();
        ready.recordAttemptFailure(requestedAt.minusMinutes(1), "TIMEOUT"); // 예약 큐에서 실패하던 배송
        Shipment booked = bookedShipment();

        assertThat(ready.requestCancel(requestedAt)).isTrue();
        assertThat(booked.requestCancel(requestedAt)).isTrue();

        for (Shipment shipment : new Shipment[]{ready, booked}) {
            assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCEL_PENDING);
            assertThat(shipment.getCancelRequestedAt()).isEqualTo(requestedAt);
            assertThat(shipment.getNextAttemptAt()).isNull();
            assertThat(shipment.getAttemptCount()).isZero();
            assertThat(shipment.getLastError()).isNull();
        }
    }

    @Test
    @DisplayName("#671 택배사가 거절하면 READY → 확인 필요로 멈추고 큐가 집지 않게 다음 시도 시각을 비운다. 화면에는 READY로 보인다")
    void markReviewRequired() {
        Shipment shipment = newShipment();
        shipment.recordAttemptFailure(LocalDateTime.of(2026, 11, 17, 10, 0), "TIMEOUT");

        shipment.markReviewRequired("PERMANENT 400 INVALID_FIELD_ERROR");

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.REVIEW_REQUIRED);
        assertThat(shipment.getNextAttemptAt()).isNull();
        assertThat(shipment.getAttemptCount()).isEqualTo(2);
        assertThat(shipment.getLastError()).isEqualTo("PERMANENT 400 INVALID_FIELD_ERROR");
        assertThat(shipment.getStatus().publicName()).isEqualTo("READY");
        assertThatThrownBy(() -> bookedShipment().markReviewRequired("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("#672 예약 본문은 처음 한 번만 저장되고, 예약 · 취소로 큐를 떠나면 지운다")
    void pinBookingRequestOnceAndClearOnLeave() {
        Shipment booked = newShipment();
        booked.pinBookingRequest("first");
        booked.pinBookingRequest("second"); // 재시도에서 다시 불러도 처음 본문 유지
        assertThat(booked.getBookingRequest()).isEqualTo("first");
        booked.book("404000000001", LocalDateTime.of(2026, 11, 27, 10, 0));
        assertThat(booked.getBookingRequest()).isNull();

        Shipment cancelled = newShipment();
        cancelled.pinBookingRequest("first");
        cancelled.requestCancel(LocalDateTime.of(2026, 11, 27, 10, 0));
        assertThat(cancelled.getBookingRequest()).isNull();
        assertThatThrownBy(() -> cancelled.pinBookingRequest("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("#671 확인 필요 배송도 구매자가 취소하면 CANCEL_PENDING이 되어 취소 큐로 넘어간다")
    void requestCancelFromReviewRequired() {
        Shipment shipment = newShipment();
        shipment.markReviewRequired("PERMANENT 400");
        LocalDateTime requestedAt = LocalDateTime.of(2026, 11, 17, 10, 0);

        assertThat(shipment.requestCancel(requestedAt)).isTrue();

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCEL_PENDING);
        assertThat(shipment.getLastError()).isNull();
    }

    @Test
    @DisplayName("[S-E04] 이미 취소 중이거나 취소된 배송에 다시 취소를 누르면 false이고 처음 누른 시각을 유지한다")
    void requestCancelTwiceIsIgnored() {
        Shipment shipment = bookedShipment();
        LocalDateTime first = LocalDateTime.of(2026, 11, 17, 10, 0);
        shipment.requestCancel(first);

        assertThat(shipment.requestCancel(first.plusSeconds(5))).isFalse();
        assertThat(shipment.getCancelRequestedAt()).isEqualTo(first);

        shipment.completeCancel(first.plusSeconds(20));
        assertThat(shipment.requestCancel(first.plusSeconds(30))).isFalse();
    }

    @Test
    @DisplayName("[S-E03] 기사 2km 안(CANCEL_LOCKED)이거나 수거된 배송은 취소를 기록할 수 없다")
    void requestCancelRejectedWhenLockedOrPickedUp() {
        Shipment locked = bookedShipment();
        locked.applyCourierStatus(ShipmentStatus.CANCEL_LOCKED, "404000000001", LocalDateTime.of(2026, 11, 17, 10, 0));
        Shipment pickedUp = bookedShipment();
        pickedUp.applyCourierStatus(ShipmentStatus.PICKED_UP, "404000000001", LocalDateTime.of(2026, 11, 17, 10, 0));

        assertThatThrownBy(() -> locked.requestCancel(LocalDateTime.of(2026, 11, 17, 10, 1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pickedUp.requestCancel(LocalDateTime.of(2026, 11, 17, 10, 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("택배사가 취소하면 CANCELLED, 거절하면 CANCEL_LOCKED가 되고 취소 큐 기록을 비운다")
    void cancelResultsFromCourier() {
        LocalDateTime requestedAt = LocalDateTime.of(2026, 11, 17, 10, 0);
        Shipment cancelled = bookedShipment();
        cancelled.requestCancel(requestedAt);
        cancelled.recordAttemptFailure(requestedAt.plusSeconds(10), "TIMEOUT");
        Shipment locked = bookedShipment();
        locked.requestCancel(requestedAt);

        cancelled.completeCancel(requestedAt.plusSeconds(30));
        locked.lockCancel();

        assertThat(cancelled.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(cancelled.getCancelledAt()).isEqualTo(requestedAt.plusSeconds(30));
        assertThat(cancelled.getAttemptCount()).isZero();
        assertThat(cancelled.getLastError()).isNull();
        assertThat(locked.getStatus()).isEqualTo(ShipmentStatus.CANCEL_LOCKED);
    }

    @Test
    @DisplayName("취소 대기가 아닌 배송에는 택배사 취소 결과를 반영할 수 없다")
    void cancelResultRequiresPending() {
        Shipment booked = bookedShipment();

        assertThatThrownBy(() -> booked.completeCancel(LocalDateTime.of(2026, 11, 17, 10, 0)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(booked::lockCancel).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("취소 대기 중에는 택배사 진행 웹훅(수거 · 배송 중 · 완료)으로 상태가 바뀌지 않는다 — 결과는 취소 큐가 정함")
    void progressWebhookIgnoredWhileCancelPending() {
        Shipment shipment = bookedShipment();
        shipment.requestCancel(LocalDateTime.of(2026, 11, 17, 10, 0));

        assertThat(shipment.applyCourierStatus(ShipmentStatus.PICKED_UP, "404000000001",
                LocalDateTime.of(2026, 11, 17, 10, 1))).isFalse();
        assertThat(shipment.applyCourierStatus(ShipmentStatus.DELIVERED, "404000000001",
                LocalDateTime.of(2026, 11, 17, 10, 2))).isFalse();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCEL_PENDING);
    }

    @Test
    @DisplayName("취소 불가(CANCEL_LOCKED) 뒤에는 수거 · 배송 웹훅이 정상으로 이어진다")
    void lockedThenPickedUp() {
        Shipment shipment = bookedShipment();
        shipment.applyCourierStatus(ShipmentStatus.CANCEL_LOCKED, "404000000001", LocalDateTime.of(2026, 11, 17, 10, 0));

        assertThat(shipment.applyCourierStatus(ShipmentStatus.PICKED_UP, "404000000001",
                LocalDateTime.of(2026, 11, 17, 10, 1))).isTrue();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.PICKED_UP);
    }

    private Shipment newShipment() {
        return new Shipment(SELLER_ORDER_ID, "몬스테라");
    }

    private Shipment bookedShipment() {
        Shipment shipment = newShipment();
        shipment.book("404000000001", LocalDateTime.of(2026, 10, 12, 10, 0));
        return shipment;
    }
}

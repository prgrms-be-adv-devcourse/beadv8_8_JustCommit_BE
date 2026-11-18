package com.justcommit.backend.market.shipment.domain;

import com.justcommit.backend.market.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Entity
@Table(name = "shipment")
@NoArgsConstructor
@Getter
public class Shipment extends BaseTimeEntity {
    // 택배 예약 · 취소 큐가 집은 배송을 다른 파드가 다시 집지 않게 미루는 시간. 택배사 타임아웃(연결 3초 · 응답 5초)보다 넉넉하게 둔다.
    static final Duration QUEUE_LEASE = Duration.ofSeconds(30);
    // 택배사 호출 실패 후 다음 시도까지 기다릴 시간 (docs/reference/schedulers.md 「택배 예약 큐」 · 「택배 취소 큐」). 마지막 값으로 계속 고정
    static final List<Duration> QUEUE_RETRY_DELAYS =
            List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(1));
    // last_error 칸 길이 (V7)
    static final int LAST_ERROR_MAX_LENGTH = 500;

    @Column(unique = true, nullable = false)
    private Long sellerOrderId;

    @Column(unique = true, length = 30)
    private String courierOrderNo;

    @Column(unique = true)
    private String trackingNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShipmentStatus status;

    private LocalDateTime bookedAt;

    private LocalDateTime pickedUpAt;

    private LocalDateTime inTransitAt;

    private LocalDateTime deliveredAt;

    private LocalDateTime cancelledAt;

    // 택배 예약 · 취소 큐가 이 배송을 다음에 집을 수 있는 시각. null이면 바로 집는다.
    // 한 배송은 READY(예약 큐) 아니면 CANCEL_PENDING(취소 큐)이라 두 큐가 같은 칸을 써도 겹치지 않는다.
    private LocalDateTime nextAttemptAt;

    // 택배사 호출에 실패한 횟수. 다음 시도까지 기다릴 시간을 정한다.
    @Column(nullable = false)
    private int attemptCount;

    // 택배사 호출의 마지막 실패 원인. 성공하거나 다른 큐로 넘어가면 비운다.
    @Column(length = LAST_ERROR_MAX_LENGTH)
    private String lastError;

    // 구매자가 취소를 누른 시각. 택배사는 이 시각의 기사 거리로 판정한다 (ADR 0026).
    private LocalDateTime cancelRequestedAt;

    // 재예약 스케줄러와 주문 취소가 같은 배송을 동시에 저장할 때 늦게 저장하는 쪽을 실패시킨다.
    @Version
    private Long version;

    // 결제 시점의 상품 요약 (예: "몬스테라 외 1건"). 택배 요청 문구와 알림 이름표로 처음 값 그대로 쓴다.
    // 그 밖의 주문 정보(구매자·판매자·받는 사람·택배비)는 결제 후 바뀌지 않아 복사하지 않고 seller_order_id로 조회한다.
    private String itemSummary;

    public Shipment(Long sellerOrderId, String itemSummary) {
        this.sellerOrderId = sellerOrderId;
        this.itemSummary = itemSummary;
        this.status = ShipmentStatus.READY;
    }

    // 수거 이후 상태(수거·출발·완료)인지. 수거되면 더 이상 취소할 수 없다.
    public boolean isPickedUp() {
        return status == ShipmentStatus.PICKED_UP
                || status == ShipmentStatus.IN_TRANSIT
                || status == ShipmentStatus.DELIVERED;
    }

    // 예약 · 취소 큐가 이 배송을 집었다. 택배사를 부르는 동안 다른 파드가 다시 집지 않게 다음 시도 시각을 잠깐 뒤로 미룬다.
    // 택배사 호출이 끝나면 결과 반영(book · completeCancel · lockCancel) 또는 실패 기록(recordAttemptFailure)이 이 값을 덮어쓴다.
    public void lease(LocalDateTime now) {
        this.nextAttemptAt = now.plus(QUEUE_LEASE);
    }

    // 택배사 호출에 실패했다. 상태는 그대로 두고 다음 시도를 10초 → 30초 → 1분(이후 고정) 뒤로 둔다.
    public void recordAttemptFailure(LocalDateTime now, String reason) {
        this.attemptCount++;
        this.nextAttemptAt = now.plus(retryDelay(this.attemptCount));
        this.lastError = truncate(reason);
    }

    // n번째 실패 뒤 기다릴 시간
    static Duration retryDelay(int failedCount) {
        int index = Math.min(failedCount, QUEUE_RETRY_DELAYS.size()) - 1;
        return QUEUE_RETRY_DELAYS.get(index);
    }

    // 구매자 취소를 기록한다 (택배사는 부르지 않음, 택배 취소 큐가 보냄). 이미 취소 중이거나 취소됐으면 false (중복 요청).
    // 기사 2km 안(CANCEL_LOCKED) · 수거 이후는 취소할 수 없다.
    public boolean requestCancel(LocalDateTime requestedAt) {
        if (this.status == ShipmentStatus.CANCEL_PENDING || this.status == ShipmentStatus.CANCELLED) {
            return false;
        }
        if (this.status != ShipmentStatus.READY && this.status != ShipmentStatus.BOOKED) {
            throw new IllegalStateException("택배 예약 전후에만 취소할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = ShipmentStatus.CANCEL_PENDING;
        this.cancelRequestedAt = requestedAt;
        resetQueue(); // 예약 큐에서 쓰던 시도 기록을 비우고 취소 큐가 바로 집게 한다
        return true;
    }

    // 택배사가 취소했다 (반송 포함).
    public void completeCancel(LocalDateTime cancelledAt) {
        requireCancelPending();
        this.status = ShipmentStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
        resetQueue();
    }

    // 택배사가 취소를 거절했다 (누른 시각에 기사가 2km 안). 배송은 그대로 진행된다.
    public void lockCancel() {
        requireCancelPending();
        this.status = ShipmentStatus.CANCEL_LOCKED;
        resetQueue();
    }

    private void requireCancelPending() {
        if (this.status != ShipmentStatus.CANCEL_PENDING) {
            throw new IllegalStateException("취소 대기 중인 배송만 택배사 취소 결과를 반영할 수 있습니다. 현재 상태: " + this.status);
        }
    }

    private void resetQueue() {
        this.nextAttemptAt = null;
        this.attemptCount = 0;
        this.lastError = null;
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= LAST_ERROR_MAX_LENGTH) {
            return reason;
        }
        return reason.substring(0, LAST_ERROR_MAX_LENGTH);
    }

    // 예약 결과를 저장하기 전에 택배사 웹훅(예: CANCEL_LOCKED)이 먼저 반영된 배송에 예약 정보만 채운다.
    // 이미 채워졌거나, 취소가 시작됐거나, 아직 READY면 false (상태는 바꾸지 않음)
    public boolean fillMissedBooking(String trackingNo, LocalDateTime bookedAt) {
        if (this.bookedAt != null
                || this.status == ShipmentStatus.READY
                || this.status == ShipmentStatus.CANCEL_PENDING
                || this.status == ShipmentStatus.CANCELLED) {
            return false;
        }
        if (this.trackingNo == null) {
            this.trackingNo = trackingNo;
        }
        this.bookedAt = bookedAt;
        resetQueue();
        return true;
    }

    public void book(String trackingNo, LocalDateTime bookedAt) {
        if (this.status != ShipmentStatus.READY) {
            throw new IllegalStateException("READY 상태의 배송만 예약할 수 있습니다. 현재 상태: " + this.status);
        }

        this.trackingNo = trackingNo;
        this.bookedAt = bookedAt;
        this.status = ShipmentStatus.BOOKED;
        resetQueue();
    }

    public void assignCourierOrderNo(LocalDate date) {
        if (this.courierOrderNo != null) {
            throw new IllegalStateException("이미 택배 주문번호가 있습니다. courierOrderNo=" + this.courierOrderNo);
        }

        this.courierOrderNo = "shipment-" +
                date.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" +
                String.format("%06d", getId());
    }

    public boolean applyCourierStatus(ShipmentStatus newStatus, String trackingNo, LocalDateTime occurredAt) {
        // 같거나 뒤로 가는 상태는 무시 (중복, 순서 뒤바뀜).
        // 취소 대기(CANCEL_PENDING) · 취소(CANCELLED)는 맨 끝이라 진행 웹훅이 오지 않음 — 취소 결과는 택배 취소 큐가 정한다
        if (newStatus.ordinal() <= this.status.ordinal()) {
            return false;
        }

        // 예약 응답을 놓친 경우 웹훅 값으로 채움
        if (this.trackingNo == null) {
            this.trackingNo = trackingNo;
        }
        this.status = newStatus;

        // 중간 웹훅을 놓쳐 단계를 건너뛰면 그 단계 시각은 비어 있다
        switch (newStatus) {
            case PICKED_UP -> this.pickedUpAt = occurredAt;
            case IN_TRANSIT -> this.inTransitAt = occurredAt;
            case DELIVERED -> this.deliveredAt = occurredAt;
            default -> { }
        }
        return true;
    }
}

package com.justcommit.backend.market.shipment.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Entity
@Table(name = "shipment")
@NoArgsConstructor
@Getter
public class Shipment extends BaseTimeEntity {
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

    // 택배사 쪽 취소 처리가 끝난 시각. CANCELLED인데 비어 있으면 스케줄러가 택배사 취소를 다시 시도한다.
    private LocalDateTime courierCancelledAt;

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

    // 배송을 취소한다. 이미 취소된 배송이면 false (중복 요청)
    public boolean cancel(LocalDateTime cancelledAt) {
        if (this.status == ShipmentStatus.CANCELLED) {
            return false;
        }
        if (isPickedUp()) {
            throw new IllegalStateException("수거된 배송은 취소할 수 없습니다. 현재 상태: " + this.status);
        }

        this.status = ShipmentStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
        return true;
    }

    // 택배사 쪽 취소 처리가 끝났음을 기록한다. READY에서 취소해 송장번호가 없었다면 다시 받아 온 송장번호로 채운다.
    public void markCourierCancelled(String trackingNo, LocalDateTime courierCancelledAt) {
        if (this.status != ShipmentStatus.CANCELLED) {
            throw new IllegalStateException("취소된 배송만 택배사 취소를 기록할 수 있습니다. 현재 상태: " + this.status);
        }
        if (this.trackingNo == null) {
            this.trackingNo = trackingNo;
        }
        this.courierCancelledAt = courierCancelledAt;
    }

    public void book(String trackingNo, LocalDateTime bookedAt) {
        if (this.status != ShipmentStatus.READY) {
            throw new IllegalStateException("READY 상태의 배송만 예약할 수 있습니다. 현재 상태: " + this.status);
        }

        this.trackingNo = trackingNo;
        this.bookedAt = bookedAt;
        this.status = ShipmentStatus.BOOKED;
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
        // 같거나 뒤로 가는 상태는 무시 (중복, 순서 뒤바뀜)
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

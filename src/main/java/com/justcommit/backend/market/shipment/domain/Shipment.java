package com.justcommit.backend.market.shipment.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
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

    // 재예약 때 처음과 같은 요청을 보내려고 결제 시점의 예약 정보를 저장한다.
    private Long sellerId;

    private String receiverName;

    private String receiverPhone;

    private String receiverAddress;

    private String itemSummary;

    // 수거되면 정산에 매출로 넘기는 택배비
    @Column(precision = 19)
    private BigDecimal shippingFee;

    public Shipment(Long sellerOrderId, Long sellerId, String receiverName, String receiverPhone,
                    String receiverAddress, String itemSummary, BigDecimal shippingFee) {
        this.sellerOrderId = sellerOrderId;
        this.sellerId = sellerId;
        this.receiverName = receiverName;
        this.receiverPhone = receiverPhone;
        this.receiverAddress = receiverAddress;
        this.itemSummary = itemSummary;
        this.shippingFee = shippingFee;
        this.status = ShipmentStatus.READY;
    }

    // 수거 이후 상태(수거·출발·완료)인지. 수거되면 더 이상 취소할 수 없다.
    public boolean isPickedUp() {
        return status == ShipmentStatus.PICKED_UP
                || status == ShipmentStatus.IN_TRANSIT
                || status == ShipmentStatus.DELIVERED;
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

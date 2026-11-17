package com.justcommit.backend.market.shipment.domain;

import com.justcommit.backend.market.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 택배 취소 결과 (ADR 0025). 택배 취소 큐가 배송 상태와 같은 트랜잭션에 한 건 남기고,
// 주문 Saga 복구가 읽어 취소됨이면 환불 · 취소 완료, 취소 불가면 주문상품을 되돌린다.
@Entity
@Table(name = "shipment_cancel_result")
@NoArgsConstructor
@Getter
public class ShipmentCancelResult extends BaseTimeEntity {

    @Column(nullable = false, unique = true)
    private Long shipmentId;

    @Column(nullable = false)
    private Long sellerOrderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Result result;

    // 취소는 됐지만 이미 수거돼 택배사가 판매자에게 반송하는지
    @Column(nullable = false)
    private boolean courierReturn;

    // 택배사 결과를 받은 시각
    @Column(nullable = false)
    private LocalDateTime decidedAt;

    // 주문 Saga가 처리한 시각. null이면 아직 처리 전
    private LocalDateTime processedAt;

    public enum Result {
        CANCELLED, // 취소됨 → 환불
        LOCKED     // 취소 불가 (누른 시각에 기사 2km 안) → 주문상품 되돌림
    }

    private ShipmentCancelResult(Shipment shipment, Result result, boolean courierReturn, LocalDateTime decidedAt) {
        this.shipmentId = shipment.getId();
        this.sellerOrderId = shipment.getSellerOrderId();
        this.result = result;
        this.courierReturn = courierReturn;
        this.decidedAt = decidedAt;
    }

    public static ShipmentCancelResult cancelled(Shipment shipment, boolean courierReturn, LocalDateTime decidedAt) {
        return new ShipmentCancelResult(shipment, Result.CANCELLED, courierReturn, decidedAt);
    }

    public static ShipmentCancelResult locked(Shipment shipment, LocalDateTime decidedAt) {
        return new ShipmentCancelResult(shipment, Result.LOCKED, false, decidedAt);
    }

    // 주문 Saga가 이 결과를 처리했다
    public void markProcessed(LocalDateTime processedAt) {
        if (this.processedAt == null) {
            this.processedAt = processedAt;
        }
    }
}

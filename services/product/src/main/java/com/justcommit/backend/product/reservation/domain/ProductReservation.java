package com.justcommit.backend.product.reservation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// orderId별 예약 기록. 행은 저장소의 upsert 쿼리로만 만들고, 예약 · 해제 모두 이 행을 잠그고 처리한다
@Entity
@Table(name = "product_reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductReservation {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public boolean isReleased() {
        return status == ReservationStatus.RELEASED;
    }
}

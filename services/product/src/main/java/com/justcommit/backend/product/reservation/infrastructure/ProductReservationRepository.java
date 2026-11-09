package com.justcommit.backend.product.reservation.infrastructure;

import com.justcommit.backend.product.reservation.domain.ProductReservation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductReservationRepository extends JpaRepository<ProductReservation, Long> {

    // 예약: 기록이 없을 때만 RESERVED로 만든다 (같은 orderId가 동시에 와도 키 충돌 없이 한 행)
    @Modifying
    @Query(value = """
            INSERT INTO product_reservation (order_id, status, created_at, updated_at)
            VALUES (:orderId, 'RESERVED', now(), now())
            ON CONFLICT (order_id) DO NOTHING
            """, nativeQuery = true)
    void insertIfAbsent(@Param("orderId") Long orderId);

    // 해제: 기록이 없어도 RELEASED로 남긴다 → 늦게 도착한 예약을 409로 막음
    @Modifying
    @Query(value = """
            INSERT INTO product_reservation (order_id, status, created_at, updated_at)
            VALUES (:orderId, 'RELEASED', now(), now())
            ON CONFLICT (order_id) DO UPDATE SET status = 'RELEASED', updated_at = now()
            """, nativeQuery = true)
    void upsertReleased(@Param("orderId") Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ProductReservation r WHERE r.orderId = :orderId")
    Optional<ProductReservation> findWithLockByOrderId(@Param("orderId") Long orderId);
}

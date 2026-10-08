package com.justcommit.backend.product.infrastructure.repository;

import com.justcommit.backend.product.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    // 판매 중이고 숨김이 아닌 상품만 이 주문으로 예약
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Product p
            SET p.status = com.justcommit.backend.product.ProductStatus.PAYMENT_PENDING,
                p.reservedOrderId = :orderId,
                p.updatedAt = CURRENT_TIMESTAMP
            WHERE p.id IN :productIds
              AND p.status = com.justcommit.backend.product.ProductStatus.ACTIVE
              AND p.hidden = false
            """)
    int reserve(@Param("orderId") Long orderId, @Param("productIds") List<Long> productIds);

    // 이 주문이 예약한 상품만 판매 중으로 되돌림
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Product p
            SET p.status = com.justcommit.backend.product.ProductStatus.ACTIVE,
                p.reservedOrderId = null,
                p.updatedAt = CURRENT_TIMESTAMP
            WHERE p.reservedOrderId = :orderId
              AND p.status = com.justcommit.backend.product.ProductStatus.PAYMENT_PENDING
            """)
    int release(@Param("orderId") Long orderId);

    // 이 주문이 예약한 상품만 판매 완료로
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Product p
            SET p.status = com.justcommit.backend.product.ProductStatus.SOLD_OUT,
                p.updatedAt = CURRENT_TIMESTAMP
            WHERE p.reservedOrderId = :orderId
              AND p.status = com.justcommit.backend.product.ProductStatus.PAYMENT_PENDING
            """)
    int markSoldOut(@Param("orderId") Long orderId);
}
package com.justcommit.backend.product.infrastructure.repository;

import com.justcommit.backend.product.domain.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

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

    // 목록: 품종을 함께 조회해 N+1 방지 (사진은 Product의 @BatchSize로 처리)
    @Override
    @EntityGraph(attributePaths = "species") //상품 조회시 품종을 함께 Join 으로 가져옴
    Page<Product> findAll(Specification<Product> spec, Pageable pageable);

    // 상세: 품종과 사진을 함께 조회
    @EntityGraph(attributePaths = {"species", "pictures"})
    Optional<Product> findWithDetailById(Long id);

}
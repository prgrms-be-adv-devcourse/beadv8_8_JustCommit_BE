package com.justcommit.backend.product.product.infrastructure;

import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.product.domain.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    // 판매 중이고 숨김이 아닌 상품만 이 주문으로 예약
    // 반환값 = 바뀐 행 수. productIds 개를 요청했는데. 그것과 다른 개수의 행이 바뀌었다면 품목 중 몇가지는 구매 불가 -> 예외
    // 동시성 : 주문 여러개가 동시에 잡으려고 할 경우, DB가 행 단위로 줄을 세워서 먼저 온쪽을 바꿈 -> 한 주문만 성공함
    @Modifying(flushAutomatically = true, clearAutomatically = true)
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
    @Modifying(flushAutomatically = true, clearAutomatically = true)
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
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Product p
            SET p.status = com.justcommit.backend.product.ProductStatus.SOLD_OUT,
                p.updatedAt = CURRENT_TIMESTAMP
            WHERE p.reservedOrderId = :orderId
              AND p.status = com.justcommit.backend.product.ProductStatus.PAYMENT_PENDING
            """)
    int markSoldOut(@Param("orderId") Long orderId);

    // 판매 재개: 이 주문이 잡은 상품 한 건만 판매 중으로 되돌림 (결제 후 취소 · 반품, 취소가 판매 완료보다 먼저 와도 맞게)
    // hide = true면 숨김도 켬 (판매자 거절 · 자동 거절 · 반품). false면 숨김 상태를 그대로 둠
    // 이미 다른 주문에 다시 팔린 상품은 reservedOrderId가 달라 건드리지 않음
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Product p
            SET p.status = com.justcommit.backend.product.ProductStatus.ACTIVE,
                p.reservedOrderId = null,
                p.hidden = CASE WHEN :hide = true THEN true ELSE p.hidden END,
                p.updatedAt = CURRENT_TIMESTAMP
            WHERE p.id = :productId
              AND p.reservedOrderId = :orderId
              AND p.status IN (com.justcommit.backend.product.ProductStatus.PAYMENT_PENDING,
                               com.justcommit.backend.product.ProductStatus.SOLD_OUT)
            """)
    int releaseItem(@Param("productId") Long productId, @Param("orderId") Long orderId, @Param("hide") boolean hide);

    // 판매자 탈퇴: 판매 중(숨김 포함)인 상품만 소프트 삭제. 결제 대기 · 판매 완료 상품은 주문 기록 때문에 남김
    // shortcut: 검수 중 · 반려 상태가 생기면(사진 검수) 조건에 함께 넣는다
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Product p
            SET p.status = com.justcommit.backend.product.ProductStatus.DELETED,
                p.updatedAt = CURRENT_TIMESTAMP
            WHERE p.sellerId = :sellerId
              AND p.status = com.justcommit.backend.product.ProductStatus.ACTIVE
            """)
    int deleteOnSaleBySeller(@Param("sellerId") Long sellerId);

    // 목록: 품종을 함께 조회해 N+1 방지 (사진은 Product의 @BatchSize로 처리)
    @Override
    @EntityGraph(attributePaths = "species") //상품 조회시 품종을 함께 Join 으로 가져옴
    Page<Product> findAll(Specification<Product> spec, Pageable pageable);

    // 상세: 품종과 사진을 함께 조회
    @EntityGraph(attributePaths = {"species", "images"})
    Optional<Product> findWithDetailById(Long id);

    // 같은 orderId로 다시 온 예약인지 확인: 이 주문이 잡고 있는 결제 대기 상품 수
    long countByReservedOrderIdAndStatusAndIdIn(Long reservedOrderId, ProductStatus status, Collection<Long> ids);

    // 다른 모듈용 다건 조회: 품종을 함께 조회해 N+1 방지
    @EntityGraph(attributePaths = "species")
    List<Product> findWithSpeciesByIdIn(List<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE) // 커밋 전까지 이 상품 행을 잠가 주문 예약(reserve)과 동시에 실행돼도 덮어쓰지 않게 함
    Optional<Product> findWithLockById(@Param("id") Long id);

    //상태별 개수 조회 결과
    interface StatusCount{
        ProductStatus getStatus();
        Long getCount();
    }

    // 판매자의 상품 상태별로 count ( 삭제 제외, hidden=null 로 들어오면 숨김 여부로 필터링 하지 않음)
    @Query("""
            SELECT p.status AS status, COUNT(p) AS count
            FROM Product p
            WHERE p.sellerId = :sellerId
              AND p.status <> com.justcommit.backend.product.ProductStatus.DELETED
              AND (:hidden IS NULL OR p.hidden = :hidden)
            GROUP BY p.status
            """)
    List<StatusCount> countByStatus(@Param("sellerId") Long sellerId, @Param("hidden") Boolean hidden);
}
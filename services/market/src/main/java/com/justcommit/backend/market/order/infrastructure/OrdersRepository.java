package com.justcommit.backend.market.order.infrastructure;

import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.order.domain.Orders;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrdersRepository extends JpaRepository<Orders, Long> {
    Page<Orders> findByBuyerId(Long buyerId, Pageable pageable);

    Optional<Orders> findByIdAndBuyerId(Long orderId, Long buyerId);

    // 스케줄러 후보 조회라 잠그지 않는다. 잠금은 주문마다 findByIdForUpdateSkipLocked로 한다.
    List<Orders> findTop100ByStatusAndCreatedAtBeforeOrderByIdAsc(OrderStatus status, LocalDateTime cutoff);

    // Saga 복구 후보: 결제 처리 중이고 마지막 시도에서 유예가 지난 주문 id, 오래된 시도부터 (부분 인덱스 사용)
    // 잠그지 않는다. 선점은 주문마다 findByIdForUpdateSkipLocked로 한다.
    @Query("""
            select o.id from Orders o
             where o.status = :processing and o.attemptedAt <= :attemptedBefore
             order by o.attemptedAt asc, o.id asc
             limit :limit
            """)
    List<Long> findRecoveryCandidateIds(@Param("processing") OrderStatus processing,
                                        @Param("attemptedBefore") LocalDateTime attemptedBefore,
                                        @Param("limit") int limit);

    // 마지막 복구도 결과 불명: 자기 시도 번호일 때만 확인 필요로 바꾸고 시도 번호를 올린다 (ADR 0023)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Orders o
               set o.status = :reviewRequired, o.attemptNo = o.attemptNo + 1,
                   o.lastError = :lastError, o.updatedAt = :now
             where o.id = :orderId and o.status = :processing and o.attemptNo = :attemptNo
            """)
    int requireReviewIfAttempt(@Param("orderId") Long orderId,
                               @Param("attemptNo") int attemptNo,
                               @Param("lastError") String lastError,
                               @Param("now") LocalDateTime now,
                               @Param("processing") OrderStatus processing,
                               @Param("reviewRequired") OrderStatus reviewRequired);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Orders o where o.id = :orderId")
    Optional<Orders> findLockedById(@Param("orderId") Long orderId);

    // 스케줄러용: 다른 파드 · 요청이 잡은 주문은 기다리지 않고 빈 값을 받아 건너뛴다.
    @Query(value = "select * from orders where id = :orderId for update skip locked", nativeQuery = true)
    Optional<Orders> findByIdForUpdateSkipLocked(@Param("orderId") Long orderId);

    // TX2 결제 시작 (ADR 0023): 결제 대기이고 기한 안이면 결제 처리 중으로 바꾸고 시도 번호를 올린다.
    // 행 잠금 없이 조건부 UPDATE 한 번이라, 만료 · 다른 요청과 겹쳐도 한쪽만 1건이 된다.
    // 벌크 UPDATE라 영속성 컨텍스트를 거치지 않으므로 비운 뒤 다시 읽는다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Orders o
               set o.status = :processing, o.attemptNo = o.attemptNo + 1, o.attemptedAt = :now,
                   o.lastError = null, o.updatedAt = :now
             where o.id = :orderId and o.status = :pending and o.createdAt > :createdAfter
            """)
    int startPaymentAttempt(@Param("orderId") Long orderId,
                            @Param("now") LocalDateTime now,
                            @Param("createdAfter") LocalDateTime createdAfter,
                            @Param("pending") OrderStatus pending,
                            @Param("processing") OrderStatus processing);

    // TX3 결과 반영 (ADR 0023): 결제 처리 중이고 자기 시도 번호일 때만 바꾼다. 늦게 온 이전 시도 결과는 0건이다.
    // 결과 불명이면 to = PAYMENT_PROCESSING으로 last_error만 남긴다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Orders o
               set o.status = :to, o.lastError = :lastError, o.updatedAt = :now
             where o.id = :orderId and o.status = :processing and o.attemptNo = :attemptNo
            """)
    int applyPaymentAttempt(@Param("orderId") Long orderId,
                            @Param("attemptNo") int attemptNo,
                            @Param("to") OrderStatus to,
                            @Param("lastError") String lastError,
                            @Param("now") LocalDateTime now,
                            @Param("processing") OrderStatus processing);

    // 탈퇴 확인: 구매자로 진행 중인 주문 수. 주문상품이 여러 개여도 주문 하나로 센다.
    @Query("""
            select count(distinct o.id) from Orders o
            join o.sellerOrders s
            join s.items i
            where o.buyerId = :buyerId
              and o.status in :orderStatuses
              and i.status in :itemStatuses
            """)
    long countActiveByBuyerId(@Param("buyerId") Long buyerId,
                              @Param("orderStatuses") Collection<OrderStatus> orderStatuses,
                              @Param("itemStatuses") Collection<OrderItemStatus> itemStatuses);
}

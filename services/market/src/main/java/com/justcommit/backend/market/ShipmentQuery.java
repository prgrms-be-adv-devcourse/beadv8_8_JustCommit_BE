package com.justcommit.backend.market;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ShipmentQuery {

    // 판매자 주문의 가는 길 배송만 돌려준다. 반품 배송은 보지 않는다 (#382)
    ShipmentResult findBySellerOrderId(Long sellerOrderId);
    Map<Long, ShipmentResult> findBySellerOrderIds(List<Long> sellerOrderIds);

    // 배송 id로 조회한다 (방향 무관). 반품 도착 처리가 반품 배송 상태를 읽는다 (#384). 없는 id는 맵에서 빠진다
    Map<Long, ShipmentResult> findByShipmentIds(List<Long> shipmentIds);

    // 주문 Saga가 아직 처리하지 않은 택배 취소 결과 id, 오래된 것부터. 잠그지 않는다
    List<Long> findUnprocessedCancelResultIds(int limit);

    // 처리 전인 택배 취소 결과를 SKIP LOCKED로 잠근다. 다른 파드가 잡았거나 이미 처리했으면 빈 값.
    // 호출한 쪽 트랜잭션 안에서만 부른다 (처리 표시까지 같은 트랜잭션)
    Optional<ShipmentCancelResultView> lockUnprocessedCancelResult(Long resultId);

    // 주문 Saga가 이 택배 취소 결과를 처리했다. lockUnprocessedCancelResult와 같은 트랜잭션에서 부른다
    void markCancelResultProcessed(Long resultId, LocalDateTime processedAt);
}

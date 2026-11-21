package com.justcommit.backend.market;

import java.util.Optional;

// 화면용 배송 조회 (#588). 택배사를 부르므로 트랜잭션 안에서 부르지 않는다
public interface ShipmentTrackingQuery {

    // 판매자 주문의 가는 길 배송. 배송이 아직 없으면 빈 값. 권한 확인은 부르는 쪽이 한다
    Optional<ShipmentTracking> track(Long sellerOrderId);

    // 반품 배송 (#617). 반품 신청에 연결된 배송 id로 찾고, 반품 배송이 아니면 빈 값. 권한 확인은 부르는 쪽이 한다
    Optional<ShipmentTracking> trackReturn(Long shipmentId);
}

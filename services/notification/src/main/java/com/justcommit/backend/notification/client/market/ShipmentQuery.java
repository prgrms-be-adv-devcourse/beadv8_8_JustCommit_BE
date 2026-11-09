package com.justcommit.backend.notification.client.market;

// 알림 문구용 배송 정보 조회 (market 내부 API로 교체 예정)
public interface ShipmentQuery {

    ShipmentResult findBySellerOrderId(Long sellerOrderId);
}

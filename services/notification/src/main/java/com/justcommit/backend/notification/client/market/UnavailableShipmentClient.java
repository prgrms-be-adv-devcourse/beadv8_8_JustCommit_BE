package com.justcommit.backend.notification.client.market;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import org.springframework.stereotype.Component;

// shortcut: market 배송 조회 내부 API가 정해지기 전 임시 구현이라 호출하면 503, 연결하는 Task에서 교체
// NotificationService는 조회 실패 시 상품 요약 없이 알림을 만든다
@Component
class UnavailableShipmentClient implements ShipmentQuery {

    @Override
    public ShipmentResult findBySellerOrderId(Long sellerOrderId) {
        throw new BusinessException(CommonErrorCode.INTERNAL_API_UNAVAILABLE);
    }
}

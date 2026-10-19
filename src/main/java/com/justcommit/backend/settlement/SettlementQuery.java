package com.justcommit.backend.settlement;

import java.time.LocalDateTime;
import java.util.Optional;

/** 판매자 주문의 실제 정산 완료 시각을 조회하는 공개 API. */
public interface SettlementQuery {
    Optional<LocalDateTime> findSettledAtBySellerOrderId(Long sellerOrderId);
}

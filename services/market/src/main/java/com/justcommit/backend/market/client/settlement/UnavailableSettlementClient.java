package com.justcommit.backend.market.client.settlement;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Component;

// shortcut: settlement 내부 API가 정해지기 전 임시 구현이라 호출하면 503, 맡은 Task가 정해지면 교체
@Component
class UnavailableSettlementClient implements SettlementQuery {

    @Override
    public Optional<LocalDateTime> findSettledAtBySellerOrderId(Long sellerOrderId) {
        throw new BusinessException(CommonErrorCode.INTERNAL_API_UNAVAILABLE);
    }
}

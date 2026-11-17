package com.justcommit.backend.settlement.application.payout;

import com.justcommit.backend.settlement.domain.Settlement;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 확보 트랜잭션에서 읽어 둔 지급 요청 값과 이 실행의 임대 시각 (트랜잭션 밖 호출 · 결과 반영에 씀)
record PayoutTarget(Long settlementId, Long sellerId, BigDecimal payoutAmount, BigDecimal fee,
                    LocalDateTime leasedUntil) {

    static PayoutTarget of(Settlement settlement, LocalDateTime leasedUntil) {
        return new PayoutTarget(settlement.getId(), settlement.getSellerId(), settlement.getPayoutAmount(),
                settlement.getFee(), leasedUntil);
    }
}

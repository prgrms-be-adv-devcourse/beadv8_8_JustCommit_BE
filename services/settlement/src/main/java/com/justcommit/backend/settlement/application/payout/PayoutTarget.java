package com.justcommit.backend.settlement.application.payout;

import com.justcommit.backend.settlement.domain.Settlement;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 확보 트랜잭션에서 읽어 둔 지급 요청 값과 이 실행의 임대 시각 (트랜잭션 밖 호출 · 결과 반영에 씀)
record PayoutTarget(Long settlementId, Long sellerId, Long sellerOrderId, BigDecimal payoutAmount, BigDecimal fee,
                    BigDecimal shippingFee, BigDecimal sellerBurdenAmount, LocalDateTime leasedUntil) {

    // 배송비가 없는 정산(옛 토픽으로만 받음)은 0으로 보낸다
    static PayoutTarget of(Settlement settlement, LocalDateTime leasedUntil) {
        BigDecimal shippingFee = settlement.getShippingFee() != null ? settlement.getShippingFee() : BigDecimal.ZERO;
        return new PayoutTarget(settlement.getId(), settlement.getSellerId(), settlement.getSellerOrderId(),
                settlement.getPayoutAmount(), settlement.getFee(), shippingFee, settlement.getSellerBurdenAmount(),
                leasedUntil);
    }
}

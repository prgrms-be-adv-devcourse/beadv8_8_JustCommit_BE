package com.justcommit.backend.payment;

import java.math.BigDecimal;

public interface PaymentUseCase {

    /**
     * 주문 결제 금액만큼 구매자의 예치금을 차감한다.
     *
     * <p>잔액 부족, 지갑 없음, 중복 요청은 예외가 아니라 결과 상태로 돌려준다.</p>
     *
     * @param orderId 주문 ID. 같은 주문은 한 번만 차감된다
     * @param buyerId 구매자 회원 ID
     * @param amount  결제 금액 (0보다 커야 함)
     * @return {@link PaymentStatus#PAID}, {@link PaymentStatus#ALREADY_PAID},
     *         {@link PaymentStatus#INSUFFICIENT_BALANCE}, {@link PaymentStatus#WALLET_NOT_FOUND} 중 하나
     * @throws com.justcommit.backend.common.exception.BusinessException 인자가 null이거나 금액이 0 이하일 때
     */
    PaymentResult pay(Long orderId, Long buyerId, BigDecimal amount);

    void distributeSettlement(
            Long settlementId,
            Long sellerId,
            BigDecimal payoutAmount,
            BigDecimal fee
            // BigDecimal shippingFee
    );

}

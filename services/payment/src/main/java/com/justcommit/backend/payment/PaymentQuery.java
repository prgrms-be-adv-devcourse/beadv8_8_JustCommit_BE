package com.justcommit.backend.payment;

import java.math.BigDecimal;

public interface PaymentQuery {

    /**
     * 구매자의 현재 예치금 잔액을 조회한다.
     *
     * @param buyerId 구매자 회원 ID
     * @return 현재 잔액
     * @throws com.justcommit.backend.common.exception.BusinessException 지갑이 없으면 WALLET_NOT_FOUND
     */
    BigDecimal getBalance(Long buyerId);

}

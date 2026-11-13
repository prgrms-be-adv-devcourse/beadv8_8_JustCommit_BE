package com.justcommit.backend.market.order.presentation.internal;

// market이 탈퇴를 막는 사유 코드 (payment · settlement와 같은 공통 응답 규격)
public enum WithdrawalBlockingReason {
    ACTIVE_BUYER_ORDER,
    ACTIVE_SELLER_ORDER,
}

package com.justcommit.backend.payment.presentation.internal.dto;

// payment가 탈퇴를 막는 사유 코드 (market · settlement와 같은 공통 응답 규격)
public enum WithdrawalBlockingReason {
    // 지갑 잔액 남음
    WALLET_BALANCE_REMAINING,
    // 진행 중 충전(PENDING · REVIEW_REQUIRED) 또는 인출(PENDING)
    TRANSACTION_IN_PROGRESS,
    // 판매자 미수금 남음
    SELLER_DEBT_REMAINING,
}

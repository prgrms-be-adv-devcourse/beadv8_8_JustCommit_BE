package com.justcommit.backend.settlement.presentation.dto;

// settlement가 탈퇴를 막는 사유 코드 (market, payment와 같은 공통 응답 규격)
public enum WithdrawalBlockingReason {
    // 완료되지 않은 정산(PENDING, FAILED)이 남음
    INCOMPLETE_SETTLEMENT,
}

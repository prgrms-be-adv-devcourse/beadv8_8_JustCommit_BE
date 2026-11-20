package com.justcommit.backend.settlement.presentation.dto;

import java.util.ArrayList;
import java.util.List;

// 탈퇴 확인 응답 (공통 규격: hasWithdrawalBlocker, blockingReasons, details)
// hasWithdrawalBlocker는 blockingReasons로만 계산한다 (member가 둘이 다르면 확인 실패로 처리)
public record WithdrawalCheckResponse(
        boolean hasWithdrawalBlocker,
        List<WithdrawalBlockingReason> blockingReasons,
        Details details
) {

    public record Details(long incompleteSettlementCount) {
    }

    public static WithdrawalCheckResponse of(long incompleteSettlementCount) {
        List<WithdrawalBlockingReason> reasons = new ArrayList<>();
        if (incompleteSettlementCount > 0) {
            reasons.add(WithdrawalBlockingReason.INCOMPLETE_SETTLEMENT);
        }
        return new WithdrawalCheckResponse(
                !reasons.isEmpty(),
                List.copyOf(reasons),
                new Details(incompleteSettlementCount));
    }
}

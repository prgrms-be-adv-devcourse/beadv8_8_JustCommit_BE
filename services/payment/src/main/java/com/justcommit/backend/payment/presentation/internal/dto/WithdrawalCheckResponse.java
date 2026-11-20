package com.justcommit.backend.payment.presentation.internal.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

// 탈퇴 확인 응답 (공통 규격: hasWithdrawalBlocker · blockingReasons · details)
// hasWithdrawalBlocker는 blockingReasons로만 계산한다 (member가 둘이 다르면 확인 실패로 처리)
public record WithdrawalCheckResponse(
        boolean hasWithdrawalBlocker,
        List<WithdrawalBlockingReason> blockingReasons,
        Details details
) {

    public record Details(BigDecimal walletBalance, boolean hasPendingTransactions, BigDecimal outstandingDebt) {
    }

    public static WithdrawalCheckResponse of(BigDecimal walletBalance, boolean hasPendingTransactions,
                                             BigDecimal outstandingDebt) {
        List<WithdrawalBlockingReason> reasons = new ArrayList<>();
        if (walletBalance.signum() > 0) {
            reasons.add(WithdrawalBlockingReason.WALLET_BALANCE_REMAINING);
        }
        if (hasPendingTransactions) {
            reasons.add(WithdrawalBlockingReason.TRANSACTION_IN_PROGRESS);
        }
        if (outstandingDebt.signum() > 0) {
            reasons.add(WithdrawalBlockingReason.SELLER_DEBT_REMAINING);
        }
        return new WithdrawalCheckResponse(
                !reasons.isEmpty(),
                List.copyOf(reasons),
                new Details(walletBalance, hasPendingTransactions, outstandingDebt));
    }

    // 지갑이 없는 회원: 잔액 0 · 미수금 0 · 진행 중 거래 없음
    public static WithdrawalCheckResponse noWallet() {
        return of(BigDecimal.ZERO, false, BigDecimal.ZERO);
    }
}

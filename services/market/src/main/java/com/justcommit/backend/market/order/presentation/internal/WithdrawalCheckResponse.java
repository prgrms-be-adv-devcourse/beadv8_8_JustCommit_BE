package com.justcommit.backend.market.order.presentation.internal;

import java.util.ArrayList;
import java.util.List;

// 탈퇴 확인 응답 (공통 규격: hasWithdrawalBlocker · blockingReasons · details)
public record WithdrawalCheckResponse(
        boolean hasWithdrawalBlocker,
        List<WithdrawalBlockingReason> blockingReasons,
        Details details
) {

    public record Details(long activeBuyerOrderCount, long activeSellerOrderCount) {
    }

    public static WithdrawalCheckResponse of(long activeBuyerOrderCount, long activeSellerOrderCount) {
        List<WithdrawalBlockingReason> reasons = new ArrayList<>();
        if (activeBuyerOrderCount > 0) {
            reasons.add(WithdrawalBlockingReason.ACTIVE_BUYER_ORDER);
        }
        if (activeSellerOrderCount > 0) {
            reasons.add(WithdrawalBlockingReason.ACTIVE_SELLER_ORDER);
        }
        return new WithdrawalCheckResponse(
                !reasons.isEmpty(),
                List.copyOf(reasons),
                new Details(activeBuyerOrderCount, activeSellerOrderCount));
    }
}

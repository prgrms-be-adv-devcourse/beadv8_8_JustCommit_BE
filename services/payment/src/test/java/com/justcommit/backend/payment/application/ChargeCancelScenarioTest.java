package com.justcommit.backend.payment.application;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 시나리오 자리표시: 충전 취소가 아직 없음 (#381)
// 충전 취소를 만드는 PR에서 본문을 채우고 @Disabled를 뗀다 (docs/testing/scenarios.md)
@Disabled("기능 없음 · 충전 취소 #381")
class ChargeCancelScenarioTest {

    @Test
    @DisplayName("[S-P01] 5만 원 충전 · 1만 원 사용 → 4만 원 취소, 토스가 거절하면 충전금 복원 · 취소 기회 유지")
    void cancelsUnusedChargeAndRestoresOnTossRejection() {
    }
}

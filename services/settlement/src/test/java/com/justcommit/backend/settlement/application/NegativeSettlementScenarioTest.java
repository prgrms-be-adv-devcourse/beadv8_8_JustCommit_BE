package com.justcommit.backend.settlement.application;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 시나리오 자리표시: 음수 정산 · 미수금이 아직 없음 (#433 · 테스트 #435)
// 기능을 만드는 PR에서 본문을 채우고 @Disabled를 뗀다 (docs/testing/scenarios.md)
@Disabled("기능 없음 · 음수 정산 · 미수금 #433")
class NegativeSettlementScenarioTest {

    @Test
    @DisplayName("[S-G05] 전부 반품 → 음수 정산 → 판매자 정산금 · 충전금에서 회수 → 부족분 미수금 → 다음 입금에서 먼저 차감, 미수금 있으면 인출 불가")
    void negativeSettlementCreatesReceivable() {
    }
}

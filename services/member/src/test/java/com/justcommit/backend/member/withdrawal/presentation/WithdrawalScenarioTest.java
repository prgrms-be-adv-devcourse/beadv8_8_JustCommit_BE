package com.justcommit.backend.member.withdrawal.presentation;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 시나리오 자리표시: 회원 탈퇴 API가 아직 없음 (#407)
// 탈퇴 API를 만드는 PR에서 본문을 채우고 @Disabled를 뗀다 (docs/testing/scenarios.md)
// 탈퇴 확인 3곳 병렬 호출 · 실패 → UNAVAILABLE은 WithdrawalCheckServiceIntegrationTest가 확인
@Disabled("기능 없음 · 회원 탈퇴 #407")
class WithdrawalScenarioTest {

    @Test
    @DisplayName("[S-A08] 탈퇴 → iat <= 끊은 시각인 토큰 모두 401, Refresh 일괄 삭제")
    void withdrawalRevokesTokens() {
    }

    @Test
    @DisplayName("[S-B04] 탈퇴 확인 3곳 중 하나라도 실패(UNAVAILABLE)하면 탈퇴를 막음 (Fail-closed)")
    void withdrawalBlockedWhenCheckUnavailable() {
    }
}

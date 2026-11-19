package com.justcommit.backend.gateway.tmp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// #534 CI 실패 AI 분석 확인용 임시 테스트 — 확인 뒤 PR을 닫는다 (머지하지 않음)
class CiFailureAiProbeTest {

    private static int shippingFee(int itemPrice) {
        return itemPrice >= 30_000 ? 0 : 3_000;
    }

    @Test
    @DisplayName("배송비는 항상 3,000원이다")
    void shippingFeeIsAlwaysFixed() {
        assertThat(shippingFee(50_000)).isEqualTo(3_000);
    }
}

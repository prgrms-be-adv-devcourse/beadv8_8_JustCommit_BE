package com.justcommit.backend.market.order.application;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 시나리오 자리표시: 아직 코드가 없는 주문 · 결제 흐름 (docs/testing/scenarios.md)
// 기능을 만드는 PR에서 본문을 채우고 @Disabled를 뗀다
class OrderSagaScenarioTest {

    @Test
    @Disabled("기능 없음 · 결과 불명 자동 재확인 #281")
    @DisplayName("[S-D04] 차감 응답 타임아웃 → 같은 멱등 키로 재확인 → 실제 성공이면 결제 완료로 진행")
    void resultUnknownIsRecheckedWithSameKey() {
    }

    @Test
    @Disabled("기능 없음 · Saga 복구 스케줄러 #281")
    @DisplayName("[S-D05] 결제 대기로 멈춘 주문 → 스케줄러가 복구")
    void stuckOrderIsRecovered() {
    }

    @Test
    @Disabled("기능 없음 · 주문 생성 Idempotency-Key (이슈 확인 필요)")
    @DisplayName("[S-D06] 같은 Idempotency-Key로 주문을 두 번 요청 → 주문 1건 · 차감 1번")
    void sameIdempotencyKeyCreatesOneOrder() {
    }

    @Test
    @Disabled("기능 없음 · 수락 대기 · 판매자 승인/거절 #284")
    @DisplayName("[S-D08] 결제 → ACCEPT_PENDING → 판매자가 1개 거절 · 나머지 승인 → 거절 상품 환불 · 판매 재개(숨김), 배송 1건")
    void sellerRejectsOneAndAcceptsRest() {
    }

    @Test
    @Disabled("기능 없음 · 수락 자동 거절 #284")
    @DisplayName("[S-D09] 판매자 24시간 무응답 → 자동 거절 · 전액 환불 · 구매자에게 「응답이 없어 취소」")
    void noResponseIn24HoursAutoRejects() {
    }
}

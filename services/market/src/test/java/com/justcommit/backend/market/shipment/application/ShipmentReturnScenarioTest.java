package com.justcommit.backend.market.shipment.application;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 시나리오 자리표시: 아직 코드가 없거나 코드가 시나리오와 다른 취소 · 반품 · 배송 흐름 (docs/testing/scenarios.md)
// 기능을 만드는(또는 맞추는) PR에서 본문을 채우고 @Disabled를 뗀다
class ShipmentReturnScenarioTest {

    @Test
    @Disabled("코드와 다름 · 지금은 5분 주기 재시도, 택배 취소 큐 #490")
    @DisplayName("[S-E05] 택배사 취소 실패 → 10초 → 30초 → 1분 재시도로 취소 완료")
    void courierCancelRetriesWithBackoff() {
    }

    @Test
    @Disabled("기능 없음 · CANCEL_PENDING · 택배 취소 큐 #490")
    @DisplayName("[S-E07] BOOKED 취소 → 택배사 취소 먼저, 응답 없음 → CANCEL_PENDING · 환불 안 함 → 취소 큐로 확정")
    void bookedCancelWaitsForCourierFirst() {
    }

    @Test
    @Disabled("기능 없음 · 이미 수거 처리 · 택배 취소 큐 #490")
    @DisplayName("[S-E08] 택배사 「이미 수거」 → 수거가 요청보다 늦으면 취소 인정 + 반품 자동 접수, 빠르면 거절 · PICKED_UP")
    void alreadyPickedUpComparesTimes() {
    }

    @Test
    @Disabled("기능 없음 · 반품 (docs/services/market.md ⬜)")
    @DisplayName("[S-E06] 반품 신청(배송 완료 72시간 안, 판매자 귀책) → 판매자 알림 · 자동 구매 확정 제외 → 승인 → 반품 배송 → 도착 → 환불 → 구매자 알림")
    void returnFlow() {
    }

    @Test
    @Disabled("기능 없음 · 반품 (docs/services/market.md ⬜)")
    @DisplayName("[S-E09] 반품 신청 48시간 무응답 → 자동 승인 → 반품 도착 → 환불 · 상품 ACTIVE + 숨김")
    void returnAutoApprovedAfter48Hours() {
    }

    @Test
    @Disabled("기능 없음 · 반품 (docs/services/market.md ⬜)")
    @DisplayName("[S-E10] 반품 거절 → 그 상품 바로 구매 확정")
    void rejectedReturnConfirmsPurchase() {
    }

    @Test
    @Disabled("코드와 다름 · IN_TRANSIT이 먼저 오면 수거 처리를 채우지 않음 (확인 필요)")
    @DisplayName("[S-F05] PICKED_UP 웹훅 유실, IN_TRANSIT 먼저 → 수거 처리 1번")
    void inTransitFirstFillsPickup() {
    }
}

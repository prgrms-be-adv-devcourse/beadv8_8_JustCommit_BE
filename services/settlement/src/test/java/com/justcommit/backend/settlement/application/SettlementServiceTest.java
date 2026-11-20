package com.justcommit.backend.settlement.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.settlement.SettlementResult;
import com.justcommit.backend.settlement.domain.Settlement;
import com.justcommit.backend.settlement.domain.SettlementStatus;
import com.justcommit.backend.settlement.domain.exception.SettlementErrorCode;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SettlementServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    // 나노초까지 있는 시각: 저장 값은 마이크로초로 잘려야 한다
    private static final Instant NOW = Instant.parse("2026-11-18T01:00:00.123456789Z");
    private static final LocalDateTime NOW_MICROS = LocalDateTime.of(2026, 11, 18, 10, 0, 0, 123_456_000);

    private final SettlementRepository settlementRepository = mock(SettlementRepository.class);
    private final SettlementService settlementService =
            new SettlementService(settlementRepository, Clock.fixed(NOW, ZONE));

    @Test
    @DisplayName("새 판매자 주문을 받으면 지급하지 않고 PENDING · 다음 시도 = 지금(마이크로초 절삭)으로 저장한다")
    void savesPendingSettlement() {
        given(settlementRepository.findBySellerOrderId(10L)).willReturn(Optional.empty());
        given(settlementRepository.save(any(Settlement.class))).willAnswer(invocation -> invocation.getArgument(0));

        SettlementResult result = settlementService.settle(
                10L, 7L, new BigDecimal("30000"), new BigDecimal("3000"), BigDecimal.ZERO, "몬스테라");

        ArgumentCaptor<Settlement> saved = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementRepository).save(saved.capture());
        Settlement settlement = saved.getValue();
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(settlement.getAttemptCount()).isZero();
        assertThat(settlement.getNextAttemptAt()).isEqualTo(NOW_MICROS);
        assertThat(settlement.getCompletedAt()).isNull();
        assertThat(settlement.getLastError()).isNull();
        assertThat(settlement.getShippingFee()).isEqualByComparingTo("3000");

        assertThat(result.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(result.getCompletedAt()).isNull();
        assertThat(result.getPayoutAmount()).isEqualByComparingTo("28500");
    }

    @Test
    @DisplayName("같은 판매자 주문을 다시 받으면 새로 저장하지 않고 비어 있던 배송비만 채운다 (상태 · 다음 시도 그대로)")
    void sameSellerOrderOnlyFillsShippingFee() {
        given(settlementRepository.findBySellerOrderId(20L)).willReturn(Optional.empty());
        given(settlementRepository.save(any(Settlement.class))).willAnswer(invocation -> invocation.getArgument(0));
        settlementService.settle(20L, 7L, new BigDecimal("30000"), null, BigDecimal.ZERO, "몬스테라");

        ArgumentCaptor<Settlement> saved = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementRepository).save(saved.capture());
        Settlement first = saved.getValue();
        assertThat(first.getShippingFee()).isNull();

        // 두 번째 수신: 시각이 지나도 기존 행의 next_attempt_at은 바뀌지 않아야 한다
        SettlementService later = new SettlementService(
                settlementRepository, Clock.fixed(NOW.plusSeconds(60), ZONE));
        given(settlementRepository.findBySellerOrderId(20L)).willReturn(Optional.of(first));

        SettlementResult result = later.settle(20L, 7L, new BigDecimal("30000"), new BigDecimal("3000"),
                BigDecimal.ZERO, "몬스테라");

        verify(settlementRepository, times(1)).save(any(Settlement.class));
        assertThat(first.getShippingFee()).isEqualByComparingTo("3000");
        assertThat(first.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(first.getAttemptCount()).isZero();
        assertThat(first.getNextAttemptAt()).isEqualTo(NOW_MICROS);
        assertThat(result.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(result.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("이미 배송비가 있는 정산은 다시 받아도 배송비를 바꾸지 않는다")
    void keepsExistingShippingFee() {
        Settlement existing = Settlement.createSettlement(
                30L, 7L, new BigDecimal("30000"), new BigDecimal("3000"), BigDecimal.ZERO, NOW_MICROS, "몬스테라");
        given(settlementRepository.findBySellerOrderId(30L)).willReturn(Optional.of(existing));

        settlementService.settle(30L, 7L, new BigDecimal("30000"), new BigDecimal("5000"), BigDecimal.ZERO, "몬스테라");

        verify(settlementRepository, never()).save(any(Settlement.class));
        assertThat(existing.getShippingFee()).isEqualByComparingTo("3000");
    }

    @ParameterizedTest(name = "판매액 {0}, 부담금 {1} → 수수료 {2}, 정산금 {3}")
    @CsvSource({
            "30000, 0,     1500, 28500",   // 부담금 없음: 기존과 같은 결과
            "30000, 6000,  1500, 22500",   // 일부 반품
            "30000, 28500, 1500, 0",       // 정확히 0원
            "10000, 12000, 500,  -2500"    // 음수
    })
    @DisplayName("정산금 = 판매액 - 수수료 - 부담금으로 저장하고, 0원 · 음수도 PENDING으로 저장한다")
    void savesPayoutAmountWithSellerBurden(String saleAmount, String sellerBurdenAmount, String fee,
                                           String payoutAmount) {
        given(settlementRepository.findBySellerOrderId(40L)).willReturn(Optional.empty());
        given(settlementRepository.save(any(Settlement.class))).willAnswer(invocation -> invocation.getArgument(0));

        SettlementResult result = settlementService.settle(40L, 7L, new BigDecimal(saleAmount),
                new BigDecimal("3000"), new BigDecimal(sellerBurdenAmount), "몬스테라");

        ArgumentCaptor<Settlement> saved = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementRepository).save(saved.capture());
        Settlement settlement = saved.getValue();
        assertThat(settlement.getSellerBurdenAmount()).isEqualByComparingTo(sellerBurdenAmount);
        assertThat(settlement.getFee()).isEqualByComparingTo(fee);
        assertThat(settlement.getPayoutAmount()).isEqualByComparingTo(payoutAmount);
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(result.getPayoutAmount()).isEqualByComparingTo(payoutAmount);
    }

    @Test
    @DisplayName("부담금이 음수면 저장하지 않고 거절한다")
    void rejectsNegativeSellerBurden() {
        given(settlementRepository.findBySellerOrderId(50L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> settlementService.settle(50L, 7L, new BigDecimal("30000"), new BigDecimal("3000"),
                new BigDecimal("-1"), "몬스테라"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(SettlementErrorCode.INVALID_SELLER_BURDEN_AMOUNT));
        verify(settlementRepository, never()).save(any(Settlement.class));
    }

    @Test
    @DisplayName("이미 있는 판매자 주문이면 다른 부담금이 와도 부담금 · 정산금을 바꾸지 않는다")
    void keepsExistingSellerBurden() {
        Settlement existing = Settlement.createSettlement(
                60L, 7L, new BigDecimal("30000"), null, BigDecimal.ZERO, NOW_MICROS, "몬스테라");
        given(settlementRepository.findBySellerOrderId(60L)).willReturn(Optional.of(existing));

        settlementService.settle(60L, 7L, new BigDecimal("30000"), new BigDecimal("3000"), new BigDecimal("6000"),
                "몬스테라");

        verify(settlementRepository, never()).save(any(Settlement.class));
        assertThat(existing.getSellerBurdenAmount()).isEqualByComparingTo("0");
        assertThat(existing.getPayoutAmount()).isEqualByComparingTo("28500");
        assertThat(existing.getShippingFee()).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("전부 반품: 판매액 0, 정산금 = -부담금인 정산을 PENDING, 다음 시도 = 지금(마이크로초 절삭)으로 저장한다")
    void settleReturnedSavesNegativeSettlement() {
        given(settlementRepository.findBySellerOrderId(70L)).willReturn(Optional.empty());
        given(settlementRepository.save(any(Settlement.class))).willAnswer(invocation -> invocation.getArgument(0));

        SettlementResult result = settlementService.settleReturned(70L, 7L, new BigDecimal("9000"), "몬스테라 외 1건");

        ArgumentCaptor<Settlement> saved = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementRepository).save(saved.capture());
        Settlement settlement = saved.getValue();
        assertThat(settlement.getSaleAmount()).isEqualByComparingTo("0");
        assertThat(settlement.getFee()).isEqualByComparingTo("0");
        assertThat(settlement.getShippingFee()).isEqualByComparingTo("0");
        assertThat(settlement.getSellerBurdenAmount()).isEqualByComparingTo("9000");
        assertThat(settlement.getPayoutAmount()).isEqualByComparingTo("-9000");
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(settlement.getNextAttemptAt()).isEqualTo(NOW_MICROS);
        assertThat(settlement.getItemSummary()).isEqualTo("몬스테라 외 1건");
        assertThat(result.getPayoutAmount()).isEqualByComparingTo("-9000");
    }

    @Test
    @DisplayName("전부 반품: 같은 판매자 주문을 다시 받으면 새로 저장하지 않고 기존 정산을 그대로 둔다")
    void settleReturnedTwiceKeepsExisting() {
        Settlement existing = Settlement.createReturnedSettlement(80L, 7L, new BigDecimal("9000"), NOW_MICROS, "몬스테라");
        given(settlementRepository.findBySellerOrderId(80L)).willReturn(Optional.of(existing));

        SettlementResult result = settlementService.settleReturned(80L, 7L, new BigDecimal("12000"), "몬스테라");

        verify(settlementRepository, never()).save(any(Settlement.class));
        assertThat(existing.getSellerBurdenAmount()).isEqualByComparingTo("9000");
        assertThat(result.getPayoutAmount()).isEqualByComparingTo("-9000");
    }

    @Test
    @DisplayName("전부 반품: 구매 확정 정산이 이미 있는 판매자 주문이면 아무것도 바꾸지 않고 기존 결과를 돌려준다")
    void settleReturnedKeepsPurchaseConfirmedSettlement() {
        Settlement existing = Settlement.createSettlement(
                90L, 7L, new BigDecimal("30000"), new BigDecimal("3000"), BigDecimal.ZERO, NOW_MICROS, "몬스테라");
        given(settlementRepository.findBySellerOrderId(90L)).willReturn(Optional.of(existing));

        SettlementResult result = settlementService.settleReturned(90L, 7L, new BigDecimal("9000"), "몬스테라");

        verify(settlementRepository, never()).save(any(Settlement.class));
        assertThat(existing.getSaleAmount()).isEqualByComparingTo("30000");
        assertThat(existing.getSellerBurdenAmount()).isEqualByComparingTo("0");
        assertThat(existing.getShippingFee()).isEqualByComparingTo("3000");
        assertThat(result.getPayoutAmount()).isEqualByComparingTo("28500");
    }
}

package com.justcommit.backend.settlement.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.PaymentUseCase;
import com.justcommit.backend.settlement.SettlementResult;
import com.justcommit.backend.settlement.domain.Settlement;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

    private static final Long SELLER_ORDER_ID = 100L;
    private static final Long SELLER_ID = 10L;
    private static final String ITEM_SUMMARY = "몬스테라";

    @Mock
    SettlementRepository settlementRepository;

    @Mock
    PaymentUseCase paymentUseCase;

    @InjectMocks
    SettlementService settlementService;

    @Test
    void createsSettlementWhenNotSettledYet() {
        when(settlementRepository.findBySellerOrderId(SELLER_ORDER_ID))
                .thenReturn(Optional.empty());
        when(settlementRepository.save(any(Settlement.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SettlementResult result = settlementService.settle(
                SELLER_ORDER_ID, SELLER_ID, new BigDecimal("30000"), ITEM_SUMMARY);

        assertThat(result.getSellerOrderId()).isEqualTo(SELLER_ORDER_ID);
        assertThat(result.getSaleAmount()).isEqualByComparingTo("30000");
        assertThat(result.getFee()).isEqualByComparingTo("1500");
        assertThat(result.getPayoutAmount()).isEqualByComparingTo("28500");
        assertThat(result.getSettledAt()).isNotNull();
        assertThat(result.getItemSummary()).isEqualTo(ITEM_SUMMARY);

        verify(settlementRepository).save(argThat(settlement ->
                SELLER_ID.equals(settlement.getSellerId())
                        && ITEM_SUMMARY.equals(settlement.getItemSummary())));

        verify(paymentUseCase).distributeSettlement(
                any(), eq(SELLER_ID),
                argThat(payout -> payout.compareTo(new BigDecimal("28500")) == 0),
                argThat(fee -> fee.compareTo(new BigDecimal("1500")) == 0));
    }

    @Test
    void returnsExistingSettlementWhenAlreadySettled() {
        Settlement existing = Settlement.createSettlement(
                SELLER_ORDER_ID,
                SELLER_ID,
                new BigDecimal("30000"),
                LocalDateTime.of(2026, 10, 12, 15, 0),
                ITEM_SUMMARY);

        when(settlementRepository.findBySellerOrderId(SELLER_ORDER_ID))
                .thenReturn(Optional.of(existing));

        SettlementResult result = settlementService.settle(
                SELLER_ORDER_ID, SELLER_ID, new BigDecimal("30000"), ITEM_SUMMARY);

        assertThat(result.getSellerOrderId()).isEqualTo(SELLER_ORDER_ID);
        assertThat(result.getPayoutAmount()).isEqualByComparingTo("28500");
        assertThat(result.getSettledAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 12, 15, 0));

        verify(settlementRepository, never()).save(any());
        verify(paymentUseCase, never())
                .distributeSettlement(any(), any(), any(), any());
    }

    @Test
    void exposesOnlyActualSettlementCompletionTime() {
        LocalDateTime settledAt = LocalDateTime.of(2026, 10, 19, 12, 0);
        Settlement existing = Settlement.createSettlement(
                SELLER_ORDER_ID, SELLER_ID, new BigDecimal("30000"), settledAt, ITEM_SUMMARY);
        when(settlementRepository.findBySellerOrderId(SELLER_ORDER_ID)).thenReturn(Optional.of(existing));

        assertThat(settlementService.findSettledAtBySellerOrderId(SELLER_ORDER_ID)).contains(settledAt);
    }

    @Test
    void returnsEmptyWhenSettlementDoesNotExist() {
        when(settlementRepository.findBySellerOrderId(SELLER_ORDER_ID)).thenReturn(Optional.empty());

        assertThat(settlementService.findSettledAtBySellerOrderId(SELLER_ORDER_ID)).isEmpty();
    }

    @Test
    void rejectsNullSellerOrderId() {
        assertThatThrownBy(() ->
                settlementService.settle(null, SELLER_ID, new BigDecimal("30000"), ITEM_SUMMARY))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(settlementRepository, paymentUseCase);
    }

    @Test
    void rejectsNullSellerId() {
        assertThatThrownBy(() ->
                settlementService.settle(SELLER_ORDER_ID, null, new BigDecimal("30000"), ITEM_SUMMARY))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(settlementRepository, paymentUseCase);
    }
}
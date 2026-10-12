package com.justcommit.backend.settlement.application;

import com.justcommit.backend.common.exception.BusinessException;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

    private static final Long SELLER_ORDER_ID = 100L;
    private static final Long SELLER_ID = 10L;

    @Mock SettlementRepository settlementRepository;
    @InjectMocks SettlementService settlementService;

    @Test
    void createsSettlementWhenNotSettledYet() {
        when(settlementRepository.findBySellerOrderId(SELLER_ORDER_ID)).thenReturn(Optional.empty());
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));

        SettlementResult result = settlementService.settle(
                SELLER_ORDER_ID, SELLER_ID, new BigDecimal("30000"));

        assertThat(result.getSellerOrderId()).isEqualTo(SELLER_ORDER_ID);
        assertThat(result.getSaleAmount()).isEqualByComparingTo("30000");
        assertThat(result.getFee()).isEqualByComparingTo("1500");
        assertThat(result.getPayoutAmount()).isEqualByComparingTo("28500");
        assertThat(result.getSettledAt()).isNotNull();
        verify(settlementRepository).save(any(Settlement.class));
    }

    @Test
    void returnsExistingSettlementWhenAlreadySettled() {
        Settlement existing = Settlement.createSettlement(
                SELLER_ORDER_ID, new BigDecimal("30000"), LocalDateTime.of(2026, 10, 12, 15, 0));
        when(settlementRepository.findBySellerOrderId(SELLER_ORDER_ID)).thenReturn(Optional.of(existing));

        SettlementResult result = settlementService.settle(
                SELLER_ORDER_ID, SELLER_ID, new BigDecimal("30000"));

        assertThat(result.getSellerOrderId()).isEqualTo(SELLER_ORDER_ID);
        assertThat(result.getPayoutAmount()).isEqualByComparingTo("28500");
        assertThat(result.getSettledAt()).isEqualTo(LocalDateTime.of(2026, 10, 12, 15, 0));
        verify(settlementRepository, never()).save(any());
    }

    @Test
    void rejectsNullSellerOrderId() {
        assertThatThrownBy(() -> settlementService.settle(null, SELLER_ID, new BigDecimal("30000")))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(settlementRepository);
    }

    @Test
    void rejectsNullSellerId() {
        assertThatThrownBy(() -> settlementService.settle(SELLER_ORDER_ID, null, new BigDecimal("30000")))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(settlementRepository);
    }
}
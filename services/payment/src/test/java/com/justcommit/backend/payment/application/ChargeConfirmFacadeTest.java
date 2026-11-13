package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.domain.ChargeStatus;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import com.justcommit.backend.payment.infrastructure.pg.TossConfirmRejectedException;
import com.justcommit.backend.payment.infrastructure.pg.TossPaymentClient;
import com.justcommit.backend.payment.presentation.dto.ChargeConfirmRequest;
import com.justcommit.backend.payment.presentation.dto.ChargeConfirmResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChargeConfirmFacadeTest {

    private static final Long MEMBER_ID = 10L;
    private static final Long CHARGE_ID = 1L;
    private static final String ORDER_ID = "CHG-order";
    private static final BigDecimal AMOUNT = new BigDecimal("10000");

    @Mock ChargeService chargeService;
    @Mock TossPaymentClient tossPaymentClient;
    @InjectMocks ChargeConfirmFacade chargeConfirmFacade;

    @Test
    @DisplayName("토스 거절 후 FAILED 반영이 실패해도 거절 사유(PG_CONFIRM_FAILED)를 응답한다")
    void returnsRejectReasonWhenFailUpdateFails() {
        ChargeConfirmRequest request = confirmRequest();
        when(chargeService.startConfirm(MEMBER_ID, CHARGE_ID, request))
                .thenReturn(new ChargeConfirmResponse(CHARGE_ID, ChargeStatus.PENDING, ORDER_ID, AMOUNT, BigDecimal.ZERO, null));
        doThrow(new TossConfirmRejectedException("REJECT_CARD_PAYMENT", "카드 승인이 거절되었습니다."))
                .when(tossPaymentClient).confirm(any(), any(), any());
        doThrow(new QueryTimeoutException("db timeout"))
                .when(chargeService).fail(CHARGE_ID);

        assertThatThrownBy(() -> chargeConfirmFacade.confirmCharge(MEMBER_ID, CHARGE_ID, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.PG_CONFIRM_FAILED)
                .hasMessageContaining("REJECT_CARD_PAYMENT");

        verify(chargeService).fail(CHARGE_ID);
    }

    private ChargeConfirmRequest confirmRequest() {
        ChargeConfirmRequest request = new ChargeConfirmRequest();
        ReflectionTestUtils.setField(request, "paymentKey", "pay-key");
        ReflectionTestUtils.setField(request, "orderId", ORDER_ID);
        ReflectionTestUtils.setField(request, "amount", AMOUNT);
        return request;
    }
}

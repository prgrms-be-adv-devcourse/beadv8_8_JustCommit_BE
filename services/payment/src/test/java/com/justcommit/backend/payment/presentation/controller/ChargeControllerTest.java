package com.justcommit.backend.payment.presentation.controller;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.payment.application.ChargeConfirmFacade;
import com.justcommit.backend.payment.application.ChargeService;
import com.justcommit.backend.payment.domain.ChargeStatus;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import com.justcommit.backend.payment.presentation.dto.ChargeConfirmResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ChargeControllerTest {

    private static final Long MEMBER_ID = 10L;
    private static final Long CHARGE_ID = 1L;
    private static final String URL = "/api/v1/wallet/charges/" + CHARGE_ID + "/confirm";
    private static final String BODY = """
            {"paymentKey": "pay-key", "orderId": "CHG-order", "amount": 10000}
            """;

    @Mock ChargeService chargeService;
    @Mock ChargeConfirmFacade chargeConfirmFacade;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ChargeController(chargeService, chargeConfirmFacade))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthMember(MEMBER_ID, "BUYER"), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsCompletedMessageWhenApproved() throws Exception {
        when(chargeConfirmFacade.confirmCharge(eq(MEMBER_ID), eq(CHARGE_ID), any()))
                .thenReturn(response(ChargeStatus.APPROVED, new BigDecimal("10000"), LocalDateTime.now()));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("충전이 완료되었습니다."))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    void returnsProcessingMessageWhenPending() throws Exception {
        when(chargeConfirmFacade.confirmCharge(eq(MEMBER_ID), eq(CHARGE_ID), any()))
                .thenReturn(response(ChargeStatus.PENDING, BigDecimal.ZERO, null));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("충전 처리 중입니다."))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void returnsConflictWhenChargeIsProcessing() throws Exception {
        when(chargeConfirmFacade.confirmCharge(eq(MEMBER_ID), eq(CHARGE_ID), any()))
                .thenThrow(new BusinessException(PaymentErrorCode.CHARGE_PROCESSING));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHARGE_PROCESSING"));
    }

    private ChargeConfirmResponse response(ChargeStatus status, BigDecimal balanceAfter, LocalDateTime approvedAt) {
        return new ChargeConfirmResponse(CHARGE_ID, status, "CHG-order", new BigDecimal("10000"), balanceAfter, approvedAt);
    }
}

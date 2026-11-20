package com.justcommit.backend.payment.presentation.controller;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.payment.application.cashout.CashOutFacade;
import com.justcommit.backend.payment.application.cashout.CashOutResult;
import com.justcommit.backend.payment.application.cashout.CashOutService;
import com.justcommit.backend.payment.domain.CashOutStatus;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import com.justcommit.backend.payment.presentation.dto.CashOutHistoryResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CashOutControllerTest {

    private static final Long MEMBER_ID = 10L;
    private static final String URL = "/api/v1/wallet/cashOuts";

    @Mock CashOutFacade cashOutFacade;
    @Mock CashOutService cashOutService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new CashOutController(cashOutFacade, cashOutService))
                .setCustomArgumentResolvers(
                        new AuthenticationPrincipalArgumentResolver(),
                        new PageableHandlerMethodArgumentResolver())
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
    @DisplayName("인출 신청: 인출 건 ID, 금액, 상태, 계좌 뒤 4자리, 차감 후 잔액만 응답한다")
    void requestCashOut() throws Exception {
        when(cashOutFacade.requestCashOut(eq(MEMBER_ID), eq("key-1"), any()))
                .thenReturn(new CashOutResult(5L, new BigDecimal("3000"), CashOutStatus.COMPLETED, "4444",
                        new BigDecimal("7000")));

        mockMvc.perform(post(URL)
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":3000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cashOutId").value(5))
                .andExpect(jsonPath("$.data.amount").value(3000))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.accountLast4").value("4444"))
                .andExpect(jsonPath("$.data.balanceAfter").value(7000))
                .andExpect(content().string(not(containsString("accountNo\""))));
    }

    @Test
    @DisplayName("Idempotency-Key 헤더가 없으면 400 MISSING_HEADER")
    void missingIdempotencyKey() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":3000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));

        verifyNoInteractions(cashOutFacade);
    }

    @Test
    @DisplayName("같은 키에 다른 금액이면 409 IDEMPOTENCY_KEY_CONFLICT")
    void idempotencyConflict() throws Exception {
        when(cashOutFacade.requestCashOut(eq(MEMBER_ID), eq("key-1"), any()))
                .thenThrow(new BusinessException(PaymentErrorCode.IDEMPOTENCY_KEY_CONFLICT));

        mockMvc.perform(post(URL)
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":4000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    @DisplayName("인출 내역: 상태, 금액, 계좌 뒤 4자리, 신청 시각, 완료 시각")
    void getCashOuts() throws Exception {
        LocalDateTime requestedAt = LocalDateTime.of(2026, 11, 20, 10, 0);
        when(cashOutService.getCashOuts(eq(MEMBER_ID), any())).thenReturn(new PageImpl<>(List.of(
                new CashOutHistoryResponse(5L, new BigDecimal("3000"), CashOutStatus.COMPLETED, "4444",
                        requestedAt, requestedAt.plusSeconds(1))), PageRequest.of(0, 20), 1));

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].cashOutId").value(5))
                .andExpect(jsonPath("$.data.content[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.content[0].accountLast4").value("4444"))
                .andExpect(jsonPath("$.data.content[0].requestedAt").exists())
                .andExpect(jsonPath("$.data.content[0].completedAt").exists());
    }
}

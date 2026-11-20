package com.justcommit.backend.settlement.presentation.controller;

import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.common.security.internal.InternalKeyFilter;
import com.justcommit.backend.settlement.application.SettlementService;
import com.justcommit.backend.settlement.application.withdrawal.WithdrawalCheckService;
import com.justcommit.backend.settlement.presentation.dto.WithdrawalCheckResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InternalSettlementControllerTest {

    private static final String INTERNAL_KEY = "test-internal-key";
    private static final String WITHDRAWAL_CHECK_URL = "/internal/v1/settlements/members/{memberId}/withdrawalCheck";

    @Mock
    private SettlementService settlementService;

    @Mock
    private WithdrawalCheckService withdrawalCheckService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 실제 앱처럼 내부 키 필터를 앞에 둔다 (/internal/** 요청은 X-Internal-Key가 맞아야 통과)
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new InternalSettlementController(settlementService, withdrawalCheckService))
                .addFilters(new InternalKeyFilter(INTERNAL_KEY))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("탈퇴 확인은 member가 읽는 공통 규격(hasWithdrawalBlocker, blockingReasons, details)으로 응답한다")
    void withdrawalCheckBlocked() throws Exception {
        given(withdrawalCheckService.check(7L)).willReturn(WithdrawalCheckResponse.of(2));

        mockMvc.perform(get(WITHDRAWAL_CHECK_URL, 7L).header(InternalKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("탈퇴 확인 결과를 조회했습니다."))
                .andExpect(jsonPath("$.data.hasWithdrawalBlocker").value(true))
                .andExpect(jsonPath("$.data.blockingReasons.length()").value(1))
                .andExpect(jsonPath("$.data.blockingReasons[0]").value("INCOMPLETE_SETTLEMENT"))
                .andExpect(jsonPath("$.data.details.incompleteSettlementCount").value(2));
    }

    @Test
    @DisplayName("차단 사유가 없으면 false, [] 로 응답한다 (null이 아님)")
    void withdrawalCheckNotBlocked() throws Exception {
        given(withdrawalCheckService.check(8L)).willReturn(WithdrawalCheckResponse.of(0));

        mockMvc.perform(get(WITHDRAWAL_CHECK_URL, 8L).header(InternalKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasWithdrawalBlocker").value(false))
                .andExpect(jsonPath("$.data.blockingReasons").isArray())
                .andExpect(jsonPath("$.data.blockingReasons").isEmpty())
                .andExpect(jsonPath("$.data.details.incompleteSettlementCount").value(0));
    }

    @Test
    @DisplayName("탈퇴 확인 중 DB 오류는 5xx로 보낸다 (member가 Fail-closed로 막음)")
    void withdrawalCheckDatabaseErrorIs5xx() throws Exception {
        given(withdrawalCheckService.check(9L)).willThrow(new DataAccessResourceFailureException("db down"));

        mockMvc.perform(get(WITHDRAWAL_CHECK_URL, 9L).header(InternalKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().is5xxServerError());
    }

    @Test
    @DisplayName("탈퇴 확인에 X-Internal-Key가 없으면 401 INVALID_INTERNAL_KEY")
    void withdrawalCheckRejectsMissingInternalKey() throws Exception {
        mockMvc.perform(get(WITHDRAWAL_CHECK_URL, 7L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_INTERNAL_KEY"));

        verifyNoInteractions(withdrawalCheckService);
    }
}

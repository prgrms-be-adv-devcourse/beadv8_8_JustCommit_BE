package com.justcommit.backend.market.order.presentation.internal;

import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.common.security.internal.InternalKeyFilter;
import com.justcommit.backend.market.order.application.OrderWithdrawalCheckService;
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
class OrderInternalControllerTest {

    private static final String INTERNAL_KEY = "test-internal-key";
    private static final String URL = "/internal/v1/orders/members/{memberId}/withdrawalCheck";

    @Mock
    private OrderWithdrawalCheckService withdrawalCheckService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 실제 앱처럼 내부 키 필터를 앞에 둔다 (/internal/** 요청은 X-Internal-Key가 맞아야 통과)
        mockMvc = MockMvcBuilders.standaloneSetup(new OrderInternalController(withdrawalCheckService))
                .addFilters(new InternalKeyFilter(INTERNAL_KEY))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("진행 중 주문이 있으면 공통 규격(hasWithdrawalBlocker · blockingReasons · details)으로 응답한다")
    void blocked() throws Exception {
        given(withdrawalCheckService.check(7L)).willReturn(WithdrawalCheckResponse.of(2, 1));

        mockMvc.perform(get(URL, 7L).header(InternalKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.hasWithdrawalBlocker").value(true))
                .andExpect(jsonPath("$.data.blockingReasons[0]").value("ACTIVE_BUYER_ORDER"))
                .andExpect(jsonPath("$.data.blockingReasons[1]").value("ACTIVE_SELLER_ORDER"))
                .andExpect(jsonPath("$.data.details.activeBuyerOrderCount").value(2))
                .andExpect(jsonPath("$.data.details.activeSellerOrderCount").value(1));
    }

    @Test
    @DisplayName("주문이 없는 회원은 404가 아니라 false · [] · 건수 0")
    void notBlocked() throws Exception {
        given(withdrawalCheckService.check(8L)).willReturn(WithdrawalCheckResponse.of(0, 0));

        mockMvc.perform(get(URL, 8L).header(InternalKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasWithdrawalBlocker").value(false))
                .andExpect(jsonPath("$.data.blockingReasons").isEmpty())
                .andExpect(jsonPath("$.data.details.activeBuyerOrderCount").value(0))
                .andExpect(jsonPath("$.data.details.activeSellerOrderCount").value(0));
    }

    @Test
    @DisplayName("DB 오류는 5xx로 보낸다 (member가 Fail-closed로 막음)")
    void databaseErrorIs5xx() throws Exception {
        given(withdrawalCheckService.check(9L)).willThrow(new DataAccessResourceFailureException("db down"));

        mockMvc.perform(get(URL, 9L).header(InternalKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().is5xxServerError());
    }

    @Test
    @DisplayName("X-Internal-Key가 없거나 틀리면 401")
    void rejectsMissingOrWrongInternalKey() throws Exception {
        mockMvc.perform(get(URL, 7L))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL, 7L).header(InternalKeyFilter.HEADER, "wrong-key"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(withdrawalCheckService);
    }
}

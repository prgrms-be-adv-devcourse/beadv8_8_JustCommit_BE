package com.justcommit.backend.market.order.presentation.internal;

import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.common.security.internal.InternalKeyFilter;
import com.justcommit.backend.market.order.application.SettlementTargetQueryService;
import com.justcommit.backend.market.order.application.SettlementTargetQueryService.SettlementTarget;
import com.justcommit.backend.market.order.application.SettlementTargetQueryService.SettlementTargetPage;
import com.justcommit.backend.market.order.domain.SellerOrderSettlementAmounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 정산 대상 조회 내부 API (#657): 파라미터 검증, 응답 형식, 내부 키
@ExtendWith(MockitoExtension.class)
class SellerOrderInternalControllerTest {

    private static final String INTERNAL_KEY = "test-internal-key";
    private static final String URL = "/internal/v1/sellerOrders/settlementTargets";
    private static final String FROM = "2026-11-25T00:00:00";
    private static final String TO = "2026-11-26T00:00:00";

    @Mock
    private SettlementTargetQueryService queryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 실제 앱처럼 내부 키 필터를 앞에 둔다 (/internal/** 요청은 X-Internal-Key가 맞아야 통과)
        mockMvc = MockMvcBuilders.standaloneSetup(new SellerOrderInternalController(queryService))
                .addFilters(new InternalKeyFilter(INTERNAL_KEY))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("종류, 금액, confirmedAt, nextCursor로 응답한다")
    void response() throws Exception {
        LocalDateTime confirmedAt = LocalDateTime.of(2026, 11, 25, 10, 30);
        given(queryService.find(LocalDateTime.parse(FROM), LocalDateTime.parse(TO), 5L, 2))
                .willReturn(new SettlementTargetPage(List.of(
                        new SettlementTarget(6L, 20L, confirmedAt, SellerOrderSettlementAmounts.of(true,
                                new BigDecimal("10000"), new BigDecimal("3000"), new BigDecimal("6000"))),
                        new SettlementTarget(8L, 21L, confirmedAt, SellerOrderSettlementAmounts.of(false,
                                BigDecimal.ZERO, new BigDecimal("3000"), new BigDecimal("3000")))), 8L));

        mockMvc.perform(get(URL).header(InternalKeyFilter.HEADER, INTERNAL_KEY)
                        .param("from", FROM).param("to", TO).param("cursor", "5").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].sellerOrderId").value(6))
                .andExpect(jsonPath("$.data.items[0].sellerId").value(20))
                .andExpect(jsonPath("$.data.items[0].type").value("PURCHASE_CONFIRMED"))
                .andExpect(jsonPath("$.data.items[0].saleAmount").value(10000))
                .andExpect(jsonPath("$.data.items[0].shippingFee").value(3000))
                .andExpect(jsonPath("$.data.items[0].sellerBurdenAmount").value(6000))
                .andExpect(jsonPath("$.data.items[0].confirmedAt").exists())
                .andExpect(jsonPath("$.data.items[1].type").value("RETURNED"))
                .andExpect(jsonPath("$.data.items[1].saleAmount").value(0))
                .andExpect(jsonPath("$.data.items[1].shippingFee").value(0))
                .andExpect(jsonPath("$.data.items[1].sellerBurdenAmount").value(6000))
                .andExpect(jsonPath("$.data.nextCursor").value(8));
    }

    @Test
    @DisplayName("cursor, size를 생략하면 처음부터 100개, from == to는 허용")
    void defaults() throws Exception {
        given(queryService.find(any(), any(), any(), anyInt())).willReturn(new SettlementTargetPage(List.of(), null));

        mockMvc.perform(get(URL).header(InternalKeyFilter.HEADER, INTERNAL_KEY).param("from", FROM).param("to", FROM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());

        verify(queryService).find(LocalDateTime.parse(FROM), LocalDateTime.parse(FROM), null, 100);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101"})
    @DisplayName("size가 1~100 밖이면 400 INVALID_FIELD_ERROR")
    void invalidSize(String size) throws Exception {
        mockMvc.perform(get(URL).header(InternalKeyFilter.HEADER, INTERNAL_KEY)
                        .param("from", FROM).param("to", TO).param("size", size))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_ERROR"));

        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("from이 to보다 늦으면 400 INVALID_FIELD_ERROR")
    void fromAfterTo() throws Exception {
        mockMvc.perform(get(URL).header(InternalKeyFilter.HEADER, INTERNAL_KEY).param("from", TO).param("to", FROM))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_ERROR"));

        verifyNoInteractions(queryService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    @DisplayName("cursor가 1보다 작으면 400 INVALID_FIELD_ERROR")
    void invalidCursor(String cursor) throws Exception {
        mockMvc.perform(get(URL).header(InternalKeyFilter.HEADER, INTERNAL_KEY)
                        .param("from", FROM).param("to", TO).param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_ERROR"));

        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("from, to 중 하나라도 없으면 400 MISSING_PARAMETER")
    void missingRange() throws Exception {
        mockMvc.perform(get(URL).header(InternalKeyFilter.HEADER, INTERNAL_KEY).param("to", TO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
        mockMvc.perform(get(URL).header(InternalKeyFilter.HEADER, INTERNAL_KEY).param("from", FROM))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
        mockMvc.perform(get(URL).header(InternalKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));

        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("X-Internal-Key가 없거나 틀리면 401 (기존 내부 API와 같음)")
    void rejectsMissingOrWrongInternalKey() throws Exception {
        mockMvc.perform(get(URL).param("from", FROM).param("to", TO))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).header(InternalKeyFilter.HEADER, "wrong-key").param("from", FROM).param("to", TO))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(queryService);
    }
}

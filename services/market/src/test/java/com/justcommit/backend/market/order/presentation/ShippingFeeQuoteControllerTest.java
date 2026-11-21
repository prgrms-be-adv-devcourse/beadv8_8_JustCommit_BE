package com.justcommit.backend.market.order.presentation;

import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.market.shipment.application.ShippingFeeQuote;
import com.justcommit.backend.market.shipment.application.ShippingFeeTableService;
import com.justcommit.backend.market.shipment.domain.ShippingRegion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 주문서 배송비 미리 계산 조회 (#618). 계산 자체는 ShippingFeeTableService(주문 생성과 같은 코드) 테스트에서 본다
@ExtendWith(MockitoExtension.class)
class ShippingFeeQuoteControllerTest {

    @Mock ShippingFeeTableService shippingFeeTableService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ShippingFeeQuoteController(shippingFeeTableService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("판매자가 여러 명이면 판매자마다 같은 배송비(기본 + 제주 할증)를 돌려주고, 같은 판매자는 한 번만 넣는다")
    void quoteForSellers() throws Exception {
        login(10L);
        when(shippingFeeTableService.quote("63100")).thenReturn(
                new ShippingFeeQuote(ShippingRegion.JEJU, BigDecimal.valueOf(3_000), BigDecimal.valueOf(3_000)));

        mockMvc.perform(get("/api/v1/orders/shippingFees").param("zipcode", "63100").param("sellerIds", "1,2,1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.region").value("JEJU"))
                .andExpect(jsonPath("$.regionLabel").value("제주"))
                .andExpect(jsonPath("$.sellers.length()").value(2))
                .andExpect(jsonPath("$.sellers[0].sellerId").value(1))
                .andExpect(jsonPath("$.sellers[0].fee").value(6000))
                .andExpect(jsonPath("$.sellers[0].baseFee").value(3000))
                .andExpect(jsonPath("$.sellers[0].extraFee").value(3000))
                .andExpect(jsonPath("$.sellers[1].sellerId").value(2))
                .andExpect(jsonPath("$.sellers[1].fee").value(6000));
    }

    @Test
    @DisplayName("우편번호나 판매자가 없으면 400이고 계산하지 않는다")
    void missingParams() throws Exception {
        login(10L);

        mockMvc.perform(get("/api/v1/orders/shippingFees").param("sellerIds", "1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/orders/shippingFees").param("zipcode", "63100"))
                .andExpect(status().isBadRequest());
        verify(shippingFeeTableService, never()).quote(anyString());
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void requiresLogin() throws Exception {
        mockMvc.perform(get("/api/v1/orders/shippingFees").param("zipcode", "63100").param("sellerIds", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    private void login(Long memberId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthMember(memberId, "MEMBER"), null, List.of()));
    }
}

package com.justcommit.backend.market.order.presentation;

import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.common.security.JwtVerifier;
import com.justcommit.backend.common.security.config.CommonSecurityConfigurer;
import com.justcommit.backend.market.config.SecurityConfig;
import com.justcommit.backend.market.order.application.OrderReadService;
import com.justcommit.backend.market.order.application.OrderService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 판매자 수락, 거절이 market 보안 설정(SecurityConfig)을 실제로 거쳐 SELLER 토큰만 통과하는지 확인 (#284 리뷰)
// OrderControllerTest는 standaloneSetup이라 보안 필터를 거치지 않는다.
// 토큰 검증기만 목으로 두고, JWT 필터, 401/403 응답, 경로 권한은 운영과 같은 설정을 쓴다
@SpringJUnitWebConfig(SellerOrderDecisionSecurityTest.TestConfig.class)
class SellerOrderDecisionSecurityTest {

    private static final String MEMBER_TOKEN = "member-token";
    private static final String SELLER_TOKEN = "seller-token";
    private static final Long MEMBER_ID = 20L;

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, OrderController.class})
    static class TestConfig {
        @Bean
        CommonSecurityConfigurer commonSecurityConfigurer(JwtVerifier jwtVerifier) {
            return new CommonSecurityConfigurer(jwtVerifier, "test-internal-key");
        }
    }

    @MockitoBean JwtVerifier jwtVerifier;
    @MockitoBean OrderService orderService;
    @MockitoBean OrderReadService orderReadService;
    @Autowired WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        // 같은 회원이 판매자 주문의 주인이어도 토큰 권한이 MEMBER면 막혀야 한다
        given(jwtVerifier.parseAccessToken(MEMBER_TOKEN)).willReturn(new AuthMember(MEMBER_ID, "MEMBER"));
        given(jwtVerifier.parseAccessToken(SELLER_TOKEN)).willReturn(new AuthMember(MEMBER_ID, "SELLER"));
    }

    @Test
    @DisplayName("MEMBER 토큰으로 수락하면 판매자 주문 주인이어도 403, 서비스를 부르지 않음")
    void memberCannotAccept() throws Exception {
        mockMvc.perform(post("/api/v1/orders/seller/7/accept")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + MEMBER_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(orderService, never()).acceptSellerOrder(any(), any());
    }

    @Test
    @DisplayName("MEMBER 토큰으로 거절하면 판매자 주문 주인이어도 403, 서비스를 부르지 않음")
    void memberCannotReject() throws Exception {
        mockMvc.perform(post("/api/v1/orders/seller/7/reject")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + MEMBER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderItemIds\": [11]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(orderService, never()).rejectSellerOrderItems(any(), any(), any());
    }

    @Test
    @DisplayName("SELLER 토큰이면 수락, 거절이 서비스까지 간다")
    void sellerCanAcceptAndReject() throws Exception {
        mockMvc.perform(post("/api/v1/orders/seller/7/accept")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SELLER_TOKEN))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/orders/seller/7/reject")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SELLER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderItemIds\": [11]}"))
                .andExpect(status().isOk());

        verify(orderService).acceptSellerOrder(MEMBER_ID, 7L);
        verify(orderService).rejectSellerOrderItems(MEMBER_ID, 7L, List.of(11L));
    }

    @Test
    @DisplayName("토큰 없이 수락하면 401")
    void anonymousCannotAccept() throws Exception {
        mockMvc.perform(post("/api/v1/orders/seller/7/accept"))
                .andExpect(status().isUnauthorized());

        verify(orderService, never()).acceptSellerOrder(any(), any());
    }

    @Test
    @DisplayName("MEMBER 토큰으로 반품 승인, 거절하면 403, 서비스를 부르지 않음 (#383)")
    void memberCannotDecideReturn() throws Exception {
        mockMvc.perform(post("/api/v1/orders/items/11/return/approve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + MEMBER_TOKEN))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/orders/items/11/return/reject")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + MEMBER_TOKEN))
                .andExpect(status().isForbidden());

        verify(orderService, never()).approveReturn(any(), any());
        verify(orderService, never()).rejectReturn(any(), any());
    }

    @Test
    @DisplayName("SELLER 토큰이면 반품 승인, 거절이 서비스까지 간다 (#383)")
    void sellerCanDecideReturn() throws Exception {
        mockMvc.perform(post("/api/v1/orders/items/11/return/approve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SELLER_TOKEN))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/orders/items/11/return/reject")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SELLER_TOKEN))
                .andExpect(status().isOk());

        verify(orderService).approveReturn(MEMBER_ID, 11L);
        verify(orderService).rejectReturn(MEMBER_ID, 11L);
    }
}

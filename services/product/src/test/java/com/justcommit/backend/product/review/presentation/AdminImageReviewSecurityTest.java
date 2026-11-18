package com.justcommit.backend.product.review.presentation;

import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.common.security.JwtVerifier;
import com.justcommit.backend.common.security.config.CommonSecurityConfigurer;
import com.justcommit.backend.product.config.SecurityConfig;
import com.justcommit.backend.product.review.application.AdminImageReviewService;
import com.justcommit.backend.product.review.domain.enums.ReviewDecision;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 관리자 검수 API가 product 보안 설정(SecurityConfig)을 실제로 거쳐 ADMIN만 통과하는지 확인 (#344 리뷰)
// AdminImageReviewControllerTest는 standaloneSetup이라 보안 필터를 거치지 않는다
// 토큰 검증기만 목으로 두고, JWT 필터 · 403/401 응답 · 경로 권한은 운영과 같은 설정을 쓴다
@SpringJUnitWebConfig(AdminImageReviewSecurityTest.TestConfig.class)
class AdminImageReviewSecurityTest {

    private static final String MEMBER_TOKEN = "member-token";
    private static final String ADMIN_TOKEN = "admin-token";
    private static final Long ADMIN_ID = 1L;

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, AdminImageReviewController.class})
    static class TestConfig {
        @Bean
        CommonSecurityConfigurer commonSecurityConfigurer(JwtVerifier jwtVerifier) {
            return new CommonSecurityConfigurer(jwtVerifier, "test-internal-key");
        }
    }

    @MockitoBean JwtVerifier jwtVerifier;
    @MockitoBean AdminImageReviewService adminImageReviewService;
    @Autowired WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        given(jwtVerifier.parseAccessToken(MEMBER_TOKEN)).willReturn(new AuthMember(2L, "MEMBER"));
        given(jwtVerifier.parseAccessToken(ADMIN_TOKEN)).willReturn(new AuthMember(ADMIN_ID, "ADMIN"));
    }

    @Test
    @DisplayName("일반 회원 토큰으로 검수 처리 PATCH를 부르면 403, 서비스를 부르지 않음")
    void memberCannotDecide() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/imageReviews/5")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + MEMBER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"APPROVED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(adminImageReviewService, never()).decide(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("일반 회원 토큰으로 관리자 확인 대기 목록을 부르면 403")
    void memberCannotList() throws Exception {
        mockMvc.perform(get("/api/v1/admin/imageReviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + MEMBER_TOKEN))
                .andExpect(status().isForbidden());

        verify(adminImageReviewService, never()).findAwaiting(any());
    }

    @Test
    @DisplayName("토큰이 없으면 401")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/imageReviews/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"APPROVED\"}"))
                .andExpect(status().isUnauthorized());

        verify(adminImageReviewService, never()).decide(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("관리자 토큰이면 통과하고, 처리자 id는 토큰의 회원 id")
    void adminCanDecide() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/imageReviews/5")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"APPROVED\"}"))
                .andExpect(status().isOk());

        verify(adminImageReviewService).decide(5L, ADMIN_ID, ReviewDecision.APPROVED, null, null);
    }
}

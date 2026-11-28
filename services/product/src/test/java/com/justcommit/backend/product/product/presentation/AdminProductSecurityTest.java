package com.justcommit.backend.product.product.presentation;

import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.common.security.JwtVerifier;
import com.justcommit.backend.common.security.config.CommonSecurityConfigurer;
import com.justcommit.backend.product.config.SecurityConfig;
import com.justcommit.backend.product.product.application.AdminProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 관리자 상품 API(#623)가 product 보안 설정을 거쳐 ADMIN만 통과하는지 확인 (AdminImageReviewSecurityTest와 같은 방식)
@SpringJUnitWebConfig(AdminProductSecurityTest.TestConfig.class)
class AdminProductSecurityTest {

    private static final String MEMBER_TOKEN = "member-token";
    private static final String ADMIN_TOKEN = "admin-token";
    private static final Long ADMIN_ID = 1L;

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, AdminProductController.class})
    static class TestConfig {
        @Bean
        CommonSecurityConfigurer commonSecurityConfigurer(JwtVerifier jwtVerifier) {
            return new CommonSecurityConfigurer(jwtVerifier, "test-internal-key");
        }
    }

    @MockitoBean JwtVerifier jwtVerifier;
    @MockitoBean AdminProductService adminProductService;
    @Autowired WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        given(jwtVerifier.parseAccessToken(MEMBER_TOKEN)).willReturn(new AuthMember(2L, "MEMBER"));
        given(jwtVerifier.parseAccessToken(ADMIN_TOKEN)).willReturn(new AuthMember(ADMIN_ID, "ADMIN"));
    }

    @Test
    @DisplayName("일반 회원은 강제 숨김 403, 서비스를 부르지 않음")
    void memberCannotHide() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/products/10/hidden")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + MEMBER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\": true, \"reason\": \"허위 매물\"}"))
                .andExpect(status().isForbidden());

        verify(adminProductService, never()).changeHidden(any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("일반 회원은 관리자 상품 목록 403")
    void memberCannotList() throws Exception {
        mockMvc.perform(get("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + MEMBER_TOKEN))
                .andExpect(status().isForbidden());

        verify(adminProductService, never()).getProducts(any());
    }

    @Test
    @DisplayName("관리자는 강제 숨김 200, 관리자 ID · 사유를 서비스로 넘김")
    void adminCanHide() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/products/10/hidden")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\": true, \"reason\": \"허위 매물\"}"))
                .andExpect(status().isOk());

        verify(adminProductService).changeHidden(ADMIN_ID, 10L, true, "허위 매물");
    }

    @Test
    @DisplayName("관리자는 상품 목록 200")
    void adminCanList() throws Exception {
        given(adminProductService.getProducts(any())).willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/admin/products?adminHidden=true")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("hidden 값이 없으면 400")
    void hiddenRequired() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/products/10/hidden")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"허위 매물\"}"))
                .andExpect(status().isBadRequest());
    }
}

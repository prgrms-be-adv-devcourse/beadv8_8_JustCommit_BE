package com.justcommit.backend.product.review.presentation;

import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.common.security.JwtVerifier;
import com.justcommit.backend.common.security.config.CommonSecurityConfigurer;
import com.justcommit.backend.product.config.SecurityConfig;
import com.justcommit.backend.product.review.application.SellerImageReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 판매자 검수 기록 GET이 상품 조회 공개 규칙(GET /products/**)에 묻히지 않고 로그인을 요구하는지 확인 (#467 리뷰)
// 토큰 없이 product에 직접 오면 authMember가 null이라 500이 나던 경로. 운영과 같은 SecurityConfig를 띄우고 토큰 검증기만 목
@SpringJUnitWebConfig(SellerImageReviewSecurityTest.TestConfig.class)
class SellerImageReviewSecurityTest {

    private static final String SELLER_TOKEN = "seller-token";
    private static final Long SELLER_ID = 2L;

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, SellerImageReviewController.class})
    static class TestConfig {
        @Bean
        CommonSecurityConfigurer commonSecurityConfigurer(JwtVerifier jwtVerifier) {
            return new CommonSecurityConfigurer(jwtVerifier, "test-internal-key");
        }
    }

    @MockitoBean JwtVerifier jwtVerifier;
    @MockitoBean SellerImageReviewService sellerImageReviewService;
    @Autowired WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        given(jwtVerifier.parseAccessToken(SELLER_TOKEN)).willReturn(new AuthMember(SELLER_ID, "SELLER"));
    }

    @Test
    @DisplayName("토큰 없이 검수 기록을 조회하면 401, 서비스를 부르지 않음 (500이 아님)")
    void anonymousHistoryIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/products/7/imageReviews"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verify(sellerImageReviewService, never()).history(any(), any());
    }

    @Test
    @DisplayName("로그인하면 통과하고, 토큰의 회원 id로 조회")
    void sellerCanReadHistory() throws Exception {
        mockMvc.perform(get("/api/v1/products/7/imageReviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SELLER_TOKEN))
                .andExpect(status().isOk());

        verify(sellerImageReviewService).history(SELLER_ID, 7L);
    }

    @Test
    @DisplayName("상품 상세 GET은 그대로 공개 (보안에서 막히지 않고 컨트롤러 단계로 감, 이 테스트엔 상품 컨트롤러가 없어 404)")
    void productDetailStaysPublic() throws Exception {
        mockMvc.perform(get("/api/v1/products/7"))
                .andExpect(status().isNotFound());
    }
}

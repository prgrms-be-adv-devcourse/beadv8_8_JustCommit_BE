package com.justcommit.backend.market.review.presentation;

import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.common.security.JwtVerifier;
import com.justcommit.backend.common.security.config.CommonSecurityConfigurer;
import com.justcommit.backend.market.config.SecurityConfig;
import com.justcommit.backend.market.review.application.ReviewReadService;
import com.justcommit.backend.market.review.application.ReviewService;
import com.justcommit.backend.market.review.domain.ReviewTag;
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

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 리뷰 작성 API가 market 보안 설정(SecurityConfig)을 실제로 거쳐 로그인한 요청만 통과하는지 확인한다 (#582).
// 인증만 보므로 서비스는 목으로 둔다. 실제 서비스 경로는 ReviewControllerIntegrationTest에서 본다
@SpringJUnitWebConfig(ReviewSecurityTest.TestConfig.class)
class ReviewSecurityTest {

  private static final String MEMBER_TOKEN = "member-token";
  private static final Long MEMBER_ID = 10L;
  private static final String BODY = """
    {"sellerOrderId": 7, "rating": 4.5, "content": "잎이 싱싱하고 포장이 꼼꼼했어요", "tags": ["GOOD_PRICE"]}
    """;

  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  @Import({SecurityConfig.class, ReviewController.class})
  static class TestConfig {
    @Bean
    CommonSecurityConfigurer commonSecurityConfigurer(JwtVerifier jwtVerifier) {
      return new CommonSecurityConfigurer(jwtVerifier, "test-internal-key");
    }
  }

  @MockitoBean JwtVerifier jwtVerifier;
  @MockitoBean ReviewService reviewService;
  @MockitoBean ReviewReadService reviewReadService;
  @Autowired WebApplicationContext context;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context)
      .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
      .build();
    given(jwtVerifier.parseAccessToken(MEMBER_TOKEN)).willReturn(new AuthMember(MEMBER_ID, "MEMBER"));
  }

  @Test
  @DisplayName("토큰 없이 리뷰를 쓰면 401이고 서비스를 부르지 않는다")
  void anonymousCannotCreate() throws Exception {
    mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON).content(BODY))
      .andExpect(status().isUnauthorized());

    verify(reviewService, never()).create(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("로그인한 회원이면 토큰의 회원 번호로 서비스까지 가고 201이다")
  void memberCanCreate() throws Exception {
    given(reviewService.create(MEMBER_ID, 7L, new BigDecimal("4.5"), "잎이 싱싱하고 포장이 꼼꼼했어요",
      List.of(ReviewTag.GOOD_PRICE))).willReturn(100L);

    mockMvc.perform(post("/api/v1/reviews").header(HttpHeaders.AUTHORIZATION, "Bearer " + MEMBER_TOKEN)
      .contentType(MediaType.APPLICATION_JSON).content(BODY))
      .andExpect(status().isCreated());

    verify(reviewService).create(MEMBER_ID, 7L, new BigDecimal("4.5"), "잎이 싱싱하고 포장이 꼼꼼했어요",
      List.of(ReviewTag.GOOD_PRICE));
  }
}

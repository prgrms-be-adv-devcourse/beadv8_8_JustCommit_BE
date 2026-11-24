package com.justcommit.backend.market.config;

import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.common.security.JwtVerifier;
import com.justcommit.backend.common.security.config.CommonSecurityConfigurer;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 리뷰 공개 경로 (#581): market 보안 설정(SecurityConfig)을 실제로 거쳐 판매자 리뷰 목록 · 집계 GET만 토큰 없이 통과하는지 본다.
// Gateway PublicPaths와 같은 두 경로여야 한다. 리뷰 컨트롤러가 아직 없는 Task도 있어서(#582 · #583 · #584 · #585)
// 같은 경로를 받는 테스트용 컨트롤러로 보안 설정만 확인한다. 실제 응답 내용은 각 API Task에서 본다
@SpringJUnitWebConfig(ReviewPublicPathsSecurityTest.TestConfig.class)
class ReviewPublicPathsSecurityTest {

  private static final String MEMBER_TOKEN = "member-token";

  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  @Import({SecurityConfig.class, ReviewPathsController.class})
  static class TestConfig {
    @Bean
    CommonSecurityConfigurer commonSecurityConfigurer(JwtVerifier jwtVerifier) {
      return new CommonSecurityConfigurer(jwtVerifier, "test-internal-key");
    }
  }

  // 리뷰 API 경로만 받는 테스트용 컨트롤러 (응답 내용은 보지 않는다)
  @RestController
  @RequestMapping("/api/v1/reviews")
  static class ReviewPathsController {

    @GetMapping
    String list() {
      return "ok";
    }

    @GetMapping("/summary")
    String summary() {
      return "ok";
    }

    @PostMapping("/summary")
    String postSummary() {
      return "ok";
    }

    @GetMapping("/me")
    String mine() {
      return "ok";
    }

    @GetMapping("/writable")
    String writable() {
      return "ok";
    }

    @PostMapping
    String create() {
      return "ok";
    }

    @DeleteMapping
    String deleteAll() {
      return "ok";
    }

    @DeleteMapping("/{reviewId}")
    String delete(@PathVariable Long reviewId) {
      return "ok";
    }
  }

  @MockitoBean JwtVerifier jwtVerifier;
  @Autowired WebApplicationContext context;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context)
      .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
      .build();
    given(jwtVerifier.parseAccessToken(MEMBER_TOKEN)).willReturn(new AuthMember(10L, "MEMBER"));
  }

  @Test
  @DisplayName("[#581] 토큰 없이 판매자 리뷰 목록 · 집계는 market 인증을 통과한다")
  void sellerReviewListAndSummaryArePublic() throws Exception {
    mockMvc.perform(request(HttpMethod.GET, "/api/v1/reviews").param("sellerId", "7"))
      .andExpect(status().isOk());
    mockMvc.perform(request(HttpMethod.GET, "/api/v1/reviews/summary").param("sellerId", "7"))
      .andExpect(status().isOk());
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({
    "GET, /api/v1/reviews/me",
    "GET, /api/v1/reviews/writable",
    "POST, /api/v1/reviews",
    "DELETE, /api/v1/reviews/1",
    "POST, /api/v1/reviews/summary",
    "DELETE, /api/v1/reviews"
  })
  @DisplayName("[#581] 토큰 없이 내 리뷰 · 작성 가능 목록 · 작성 · 삭제, 공개 경로의 다른 메서드는 401 UNAUTHORIZED")
  void otherReviewApisRequireLogin(String method, String path) throws Exception {
    mockMvc.perform(request(HttpMethod.valueOf(method), path))
      .andExpect(status().isUnauthorized())
      .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({
    "GET, /api/v1/reviews/me",
    "GET, /api/v1/reviews/writable",
    "POST, /api/v1/reviews",
    "DELETE, /api/v1/reviews/1"
  })
  @DisplayName("[#581] MEMBER 토큰이면 내 리뷰 · 작성 가능 목록 · 작성 · 삭제가 인증을 통과한다")
  void otherReviewApisPassWithMemberToken(String method, String path) throws Exception {
    mockMvc.perform(request(HttpMethod.valueOf(method), path).header(HttpHeaders.AUTHORIZATION, "Bearer " + MEMBER_TOKEN))
      .andExpect(status().isOk());
  }
}

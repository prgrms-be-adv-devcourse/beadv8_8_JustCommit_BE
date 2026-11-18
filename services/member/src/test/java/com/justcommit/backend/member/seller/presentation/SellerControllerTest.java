package com.justcommit.backend.member.seller.presentation;

import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.member.seller.application.SellerProfileService;
import com.justcommit.backend.member.seller.application.SellerRegisterService;
import com.justcommit.backend.member.seller.domain.SellerReviewConsentPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 판매자 등록 동의 HTTP 계약 (#418): 정책 조회 응답이 현재 정책 그대로인지, 동의 없는 등록이 400 형식으로 나가는지
@ExtendWith(MockitoExtension.class)
class SellerControllerTest {

  private static final Long MEMBER_ID = 10L;

  @Mock
  SellerRegisterService sellerRegisterService;

  @Mock
  SellerProfileService sellerProfileService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new SellerController(sellerRegisterService, sellerProfileService))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(new AuthMember(MEMBER_ID, "MEMBER"), null, List.of()));
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("동의 정책을 조회하면 200과 현재 버전 · 제목 · 본문을 돌려준다")
  void getConsentPolicy_ok() throws Exception {
    mockMvc.perform(get("/api/v1/sellers/consent-policy"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(200))
        .andExpect(jsonPath("$.message").value("판매자 등록 동의 내용을 조회했습니다."))
        .andExpect(jsonPath("$.data.version").value(SellerReviewConsentPolicy.VERSION))
        .andExpect(jsonPath("$.data.title").value(SellerReviewConsentPolicy.TITLE))
        .andExpect(jsonPath("$.data.content").value(SellerReviewConsentPolicy.CONTENT));

    verifyNoInteractions(sellerRegisterService, sellerProfileService);
  }

  @Test
  @DisplayName("동의 여부 없이 판매자 등록을 요청하면 400 INVALID_FIELD_ERROR와 동의 메시지를 돌려주고 서비스를 부르지 않는다")
  void registerWithoutConsent_badRequest() throws Exception {
    mockMvc.perform(post("/api/v1/sellers")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reviewConsentVersion\":\"" + SellerReviewConsentPolicy.VERSION + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("INVALID_FIELD_ERROR"))
        .andExpect(jsonPath("$.message").value(containsString(
            "reviewConsentAgreed: 상품 검수를 위한 외부 AI 서비스 이용 및 국외 이전에 동의해 주세요.")));

    verifyNoInteractions(sellerRegisterService);
  }
}

package com.justcommit.backend.product.review.presentation;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.product.exception.ProductErrorCode;
import com.justcommit.backend.product.review.application.AdminImageReviewService;
import com.justcommit.backend.product.review.domain.enums.RejectReason;
import com.justcommit.backend.product.review.domain.enums.ReviewDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminImageReviewControllerTest {

    private static final Long ADMIN_ID = 1L;

    @Mock
    private AdminImageReviewService adminImageReviewService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminImageReviewController(adminImageReviewService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthMember(ADMIN_ID, "ADMIN"), null, List.of()));
    }

    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("목록: status 기본값 MANUAL, page · size로 조회")
    void list() throws Exception {
        given(adminImageReviewService.findAwaiting(PageRequest.of(1, 10))).willReturn(Page.empty(PageRequest.of(1, 10)));

        mockMvc.perform(get("/api/v1/admin/imageReviews").param("page", "1").param("size", "10"))
                .andExpect(status().isOk());

        verify(adminImageReviewService).findAwaiting(PageRequest.of(1, 10));
    }

    @Test
    @DisplayName("처리: 로그인한 관리자 id와 결정 · 사유 · 내용을 서비스로 넘김")
    void decide() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/imageReviews/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision": "REJECTED", "rejectReason": "OTHER", "memo": "사진에 가격표가 보여요"}
                                """))
                .andExpect(status().isOk());

        verify(adminImageReviewService).decide(5L, ADMIN_ID, ReviewDecision.REJECTED, RejectReason.OTHER, "사진에 가격표가 보여요");
    }

    @Test
    @DisplayName("처리: decision이 없으면 400, 서비스를 부르지 않음")
    void decisionRequired() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/imageReviews/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(adminImageReviewService, never()).decide(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("처리: 이미 처리된 검수면 409 REVIEW_ALREADY_PROCESSED")
    void alreadyProcessed() throws Exception {
        willThrow(new BusinessException(ProductErrorCode.REVIEW_ALREADY_PROCESSED))
                .given(adminImageReviewService).decide(5L, ADMIN_ID, ReviewDecision.APPROVED, null, null);

        mockMvc.perform(patch("/api/v1/admin/imageReviews/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"APPROVED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_ALREADY_PROCESSED"));
    }
}

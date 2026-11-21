package com.justcommit.backend.notification.presentation;

import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.notification.application.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class SseDisconnectHandlerTest {

    @Mock NotificationService notificationService;
    private MockMvc mockMvc;

    // 운영과 같이 공통 처리기와 함께 등록해 순서(이 처리기가 먼저)까지 확인한다
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new NotificationController(notificationService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(), new SseDisconnectHandler())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthMember(10L, "BUYER"), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("SSE 연결이 끊겨 난 예외는 오류 응답을 쓰지 않고 ERROR 로그도 남기지 않는다")
    void ignoresDisconnectedClient(CapturedOutput output) throws Exception {
        // 검사 예외(IOException)라 thenThrow를 못 써서 answer로 던진다
        when(notificationService.subscribe(any())).thenAnswer(invocation -> {
            throw new AsyncRequestNotUsableException("Broken pipe");
        });

        mockMvc.perform(get("/api/v1/notifications/subscribe").accept("text/event-stream"))
                .andExpect(content().string(""));

        assertThat(output).doesNotContain("Unexpected exception").doesNotContain("HttpMessageNotWritableException");
    }

    @Test
    @DisplayName("다른 예외는 지금처럼 공통 처리기가 500으로 응답한다")
    void otherExceptionsStillGoToCommonHandler(CapturedOutput output) throws Exception {
        when(notificationService.unreadCount(any())).thenThrow(new IllegalStateException("boom"));

        mockMvc.perform(get("/api/v1/notifications/unreadCount"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(output).contains("Unexpected exception");
    }
}

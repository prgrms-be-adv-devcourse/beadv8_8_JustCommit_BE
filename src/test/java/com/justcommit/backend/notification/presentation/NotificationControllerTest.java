package com.justcommit.backend.notification.presentation;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.common.response.PagingResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.notification.application.NotificationService;
import com.justcommit.backend.notification.domain.NotificationErrorCode;
import com.justcommit.backend.notification.presentation.dto.NotificationResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    private static final Long MEMBER_ID = 10L;

    @Mock NotificationService notificationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new NotificationController(notificationService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthMember(MEMBER_ID, "BUYER"), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listsNotificationsWithReadFilter() throws Exception {
        when(notificationService.list(eq(MEMBER_ID), eq(false), any(Pageable.class)))
                .thenReturn(new PagingResponse<>(List.of(response(false)), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/notifications").param("isRead", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].notificationId").value(1))
                .andExpect(jsonPath("$.content[0].type").value("SHIPMENT_DELIVERED"))
                .andExpect(jsonPath("$.content[0].isRead").value(false))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void returnsUnreadCount() throws Exception {
        when(notificationService.unreadCount(MEMBER_ID)).thenReturn(3L);

        mockMvc.perform(get("/api/v1/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3));
    }

    @Test
    void readsNotification() throws Exception {
        when(notificationService.read(MEMBER_ID, 1L)).thenReturn(response(true));

        mockMvc.perform(patch("/api/v1/notifications/1").contentType("application/json").content("{\"isRead\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isRead").value(true));
    }

    @Test
    void returnsNotFoundForOthersNotification() throws Exception {
        when(notificationService.read(MEMBER_ID, 2L))
                .thenThrow(new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));

        mockMvc.perform(patch("/api/v1/notifications/2").contentType("application/json").content("{\"isRead\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void readsAll() throws Exception {
        mockMvc.perform(patch("/api/v1/notifications").contentType("application/json").content("{\"isRead\":true}"))
                .andExpect(status().isNoContent());

        verify(notificationService).readAll(MEMBER_ID);
    }

    @Test
    void rejectsMarkingAsUnread() throws Exception {
        mockMvc.perform(patch("/api/v1/notifications").contentType("application/json").content("{\"isRead\":false}"))
                .andExpect(status().isBadRequest());

        verify(notificationService, never()).readAll(MEMBER_ID);
    }

    private NotificationResponse response(boolean isRead) {
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 16, 15, 0);
        return new NotificationResponse(1L, "SHIPMENT_DELIVERED", "배송이 완료됐어요.", 7L, isRead, createdAt,
                isRead ? createdAt.plusMinutes(1) : null);
    }
}

package com.justcommit.backend.notification.presentation;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.response.PagingResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.notification.application.NotificationService;
import com.justcommit.backend.notification.domain.NotificationErrorCode;
import com.justcommit.backend.notification.presentation.dto.NotificationListRequest;
import com.justcommit.backend.notification.presentation.dto.NotificationReadRequest;
import com.justcommit.backend.notification.presentation.dto.NotificationResponse;
import com.justcommit.backend.notification.presentation.dto.UnreadCountResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<PagingResponse<NotificationResponse>> list(@AuthenticationPrincipal AuthMember member,
                                                                     @ModelAttribute NotificationListRequest request) {
        return ResponseEntity.ok(notificationService.list(member.memberId(), request.isRead(), request.toPageable()));
    }

    // 실시간 구독 (text/event-stream). event: unread / data: 안 읽은 알림 수
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@AuthenticationPrincipal AuthMember member, HttpServletResponse response) {
        // Nginx가 응답을 모아 두지 않고 바로 보내게 한다 (끝나지 않는 응답이라 모으면 브라우저가 아무것도 못 받음)
        response.setHeader("X-Accel-Buffering", "no");
        return notificationService.subscribe(member.memberId());
    }

    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCountResponse> unreadCount(@AuthenticationPrincipal AuthMember member) {
        return ResponseEntity.ok(new UnreadCountResponse(notificationService.unreadCount(member.memberId())));
    }

    @PatchMapping("/{notificationId}")
    public ResponseEntity<NotificationResponse> read(@AuthenticationPrincipal AuthMember member,
                                                     @PathVariable Long notificationId,
                                                     @Valid @RequestBody NotificationReadRequest request) {
        requireRead(request);
        return ResponseEntity.ok(notificationService.read(member.memberId(), notificationId));
    }

    @PatchMapping
    public ResponseEntity<Void> readAll(@AuthenticationPrincipal AuthMember member,
                                        @Valid @RequestBody NotificationReadRequest request) {
        requireRead(request);
        notificationService.readAll(member.memberId());
        return ResponseEntity.noContent().build();
    }

    private void requireRead(NotificationReadRequest request) {
        if (!request.isRead()) {
            throw new BusinessException(NotificationErrorCode.ONLY_READ_SUPPORTED);
        }
    }
}

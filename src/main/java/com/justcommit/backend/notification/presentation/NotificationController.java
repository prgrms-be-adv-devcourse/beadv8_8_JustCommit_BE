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
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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

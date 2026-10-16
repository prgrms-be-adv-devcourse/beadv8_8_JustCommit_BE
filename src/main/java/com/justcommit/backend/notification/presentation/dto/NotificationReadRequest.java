package com.justcommit.backend.notification.presentation.dto;

import jakarta.validation.constraints.NotNull;

// 읽음 처리만 지원한다 ({ "isRead": true }). 안 읽음으로 되돌리기는 없다.
public record NotificationReadRequest(@NotNull Boolean isRead) {
}

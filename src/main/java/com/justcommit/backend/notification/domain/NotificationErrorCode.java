package com.justcommit.backend.notification.domain;

import com.justcommit.backend.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum NotificationErrorCode implements ErrorCode {
    // 남의 알림 id도 같은 응답을 준다 (알림이 있는지 알려 주지 않기 위해)
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),
    ONLY_READ_SUPPORTED(HttpStatus.BAD_REQUEST, "알림은 읽음 처리만 할 수 있습니다.");

    private final HttpStatus status;
    private final String message;
}

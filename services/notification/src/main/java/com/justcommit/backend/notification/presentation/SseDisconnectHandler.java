package com.justcommit.backend.notification.presentation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

// 사용자가 탭을 닫거나 새로고침해 SSE 연결이 끊기면 서버가 보내다 Broken pipe가 난다 (#598).
// 정상 상황이라 공통 처리기(GlobalExceptionHandler)보다 먼저 받아 ERROR 로그 없이 넘긴다.
// 응답을 쓰지 않는다(void): 이미 끊긴 text/event-stream에 JSON 오류를 쓰면 HttpMessageNotWritableException이 또 난다.
// 끊긴 연결은 NotificationSseRegistry가 다음 전송 때 목록에서 뺀다
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class SseDisconnectHandler {

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleDisconnected(AsyncRequestNotUsableException e) {
        log.debug("클라이언트가 끊은 SSE 연결: {}", e.getMessage());
    }
}

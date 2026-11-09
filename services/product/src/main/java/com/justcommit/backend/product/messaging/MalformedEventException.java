package com.justcommit.backend.product.messaging;

// 봉투나 payload를 읽을 수 없는 메시지. 다시 시도해도 같으므로 재시도 없이 바로 DLT로 보낸다
public class MalformedEventException extends RuntimeException {

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}

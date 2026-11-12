package com.justcommit.backend.common.messaging.crypto;

/**
 * 이벤트 필드를 풀지 못했을 때 (모르는 형식 버전 · 모르는 키 버전 · 위변조 · 잘린 값).
 * 메시지에는 평문 · 키 · 암호문 전체를 넣지 않는다.
 */
public class EventFieldCipherException extends RuntimeException {

  public EventFieldCipherException(String message) {
    super(message);
  }

  public EventFieldCipherException(String message, Throwable cause) {
    super(message, cause);
  }
}

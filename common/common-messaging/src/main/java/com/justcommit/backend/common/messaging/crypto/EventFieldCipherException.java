package com.justcommit.backend.common.messaging.crypto;

/**
 * 이벤트 필드를 풀지 못했을 때 (모르는 형식 버전 · 모르는 키 버전 · 위변조 · 잘린 값).
 * 메시지에는 평문 · 키 · 암호문을 넣지 않는다
 * 원인 예외의 메시지 · 스택 트레이스로 입력 값이 새지 않도록 원인 예외를 붙이지 않는다 (원인 예외를 받는 생성자를 두지 않음)
 */
public class EventFieldCipherException extends RuntimeException {

  public EventFieldCipherException(String message) {
    super(message);
  }
}

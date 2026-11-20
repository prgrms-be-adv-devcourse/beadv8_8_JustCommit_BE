package com.justcommit.backend.member.withdrawal.infrastructure.kakao;

// 카카오 API를 확인하지 못함 (연결 실패 · 시간 초과 · 예상하지 못한 응답). 웹훅 처리(#422)가 재시도한다
public class KakaoApiUnavailableException extends RuntimeException {

  public KakaoApiUnavailableException(String message) {
    super(message);
  }
}

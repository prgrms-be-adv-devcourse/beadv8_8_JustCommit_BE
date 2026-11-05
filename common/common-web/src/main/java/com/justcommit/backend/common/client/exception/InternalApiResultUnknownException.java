package com.justcommit.backend.common.client.exception;

public class InternalApiResultUnknownException extends InternalApiException {

  public InternalApiResultUnknownException(String group, Throwable cause) {
    super(group, group + " 서비스 응답 시간이 초과되어 처리 결과를 알 수 없습니다.", cause);
  }
}

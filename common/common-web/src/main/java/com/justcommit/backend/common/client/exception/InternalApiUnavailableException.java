package com.justcommit.backend.common.client.exception;

public class InternalApiUnavailableException extends InternalApiException {

  public InternalApiUnavailableException(String group, Throwable cause) {
    super(group, group + " 서비스에 연결하지 못했습니다.", cause);
  }
}

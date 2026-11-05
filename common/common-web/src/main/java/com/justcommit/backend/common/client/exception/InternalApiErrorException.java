package com.justcommit.backend.common.client.exception;

public class InternalApiErrorException extends InternalApiException {

  private final int status;
  private final String code;

  public InternalApiErrorException(String group, int status, String code, String message) {
    super(group, message, null);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }
}

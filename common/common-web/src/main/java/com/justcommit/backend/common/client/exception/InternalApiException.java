package com.justcommit.backend.common.client.exception;

public abstract class InternalApiException extends RuntimeException {

  private final String group;

  protected InternalApiException(String group, String message, Throwable cause) {
    super(message, cause);
    this.group = group;
  }

  public String group() {
    return group;
  }
}

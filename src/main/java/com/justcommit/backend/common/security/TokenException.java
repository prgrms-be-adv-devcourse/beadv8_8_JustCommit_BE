package com.justcommit.backend.common.security;

public class TokenException extends RuntimeException {

  public enum Reason {
    EXPIRED,
    INVALID
  }

  private final Reason reason;

  public TokenException(Reason reason, Throwable cause) {
    super(reason.name(), cause);
    this.reason = reason;
  }

  public TokenException(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }

  public Reason getReason() {
    return reason;
  }
}

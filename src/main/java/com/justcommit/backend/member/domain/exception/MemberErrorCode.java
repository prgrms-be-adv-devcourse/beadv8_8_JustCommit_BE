package com.justcommit.backend.member.domain.exception;

import com.justcommit.backend.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements ErrorCode {

  // 이메일 인증
  EMAIL_SEND_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "인증번호는 잠시 후에 다시 요청할 수 있습니다."),
  EMAIL_SEND_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "인증 메일 발송에 실패했습니다. 잠시 후 다시 시도해주세요."),
  EMAIL_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "인증번호가 만료되었거나 요청되지 않았습니다. 인증번호를 다시 요청해주세요."),
  EMAIL_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "인증번호가 일치하지 않습니다."),

  // 회원가입
  EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "이메일 인증을 먼저 완료해주세요."),
  DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
  DUPLICATE_NICKNAME(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
  DUPLICATE_PHONE(HttpStatus.CONFLICT, "이미 가입된 휴대폰 번호입니다."),

  // 로그인
  LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),

  // 토큰 재발급
  INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다. 다시 로그인해주세요."),

  // 회원 조회
  MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."),

  // 배송지
  ADDRESS_NOT_FOUND(HttpStatus.NOT_FOUND, "배송지를 찾을 수 없습니다."),
  ADDRESS_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "배송지는 최대 10개까지 등록할 수 있습니다."),

  // 판매자
  ALREADY_SELLER(HttpStatus.CONFLICT, "이미 판매자로 등록된 회원입니다."),

  // 소셜 로그인
  SOCIAL_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "소셜 로그인에 실패했습니다. 다시 시도해주세요."),
  SOCIAL_EMAIL_CONFLICT(HttpStatus.CONFLICT, "이미 이메일로 가입된 계정입니다. 이메일로 로그인해주세요."),
  SOCIAL_EMAIL_REQUIRED(HttpStatus.BAD_REQUEST, "인증된 카카오 계정 이메일이 필요합니다."),

  // 소셜 회원가입
  INVALID_SIGNUP_TOKEN(HttpStatus.UNAUTHORIZED, "가입 정보가 만료되었거나 올바르지 않습니다. 다시 진행해주세요."),
  DUPLICATE_SOCIAL_ACCOUNT(HttpStatus.CONFLICT, "이미 가입된 계정입니다. 소셜 로그인을 이용해주세요.");

  private final HttpStatus status;
  private final String message;

}

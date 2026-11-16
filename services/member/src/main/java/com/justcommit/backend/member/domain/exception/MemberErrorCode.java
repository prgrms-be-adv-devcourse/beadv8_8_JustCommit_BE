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

  // 회원 잠금 (#409): 같은 회원의 다른 요청이 잠금을 잡고 있어 대기 제한 안에 처리하지 못함
  MEMBER_BUSY(HttpStatus.CONFLICT, "같은 계정의 다른 요청을 처리하고 있습니다. 잠시 후 다시 시도해 주세요."),

  // 비밀번호 변경 (#411)
  INVALID_CURRENT_PASSWORD(HttpStatus.BAD_REQUEST, "현재 비밀번호가 올바르지 않습니다."),
  SAME_AS_CURRENT_PASSWORD(HttpStatus.BAD_REQUEST, "새 비밀번호가 현재 비밀번호와 같습니다."),
  PASSWORD_NOT_SET(HttpStatus.BAD_REQUEST, "소셜 로그인 회원은 비밀번호를 변경할 수 없습니다."),

  // 처리 결과 불명: 변경이 반영됐는지 확인하지 못함 (비밀번호 변경 · 탈퇴, 기능마다 메시지를 따로 넘김)
  RESULT_UNKNOWN(HttpStatus.SERVICE_UNAVAILABLE, "처리 결과를 확인하지 못했습니다. 잠시 후 확인해 주세요."),

  // 배송지
  ADDRESS_NOT_FOUND(HttpStatus.NOT_FOUND, "배송지를 찾을 수 없습니다."),
  ADDRESS_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "배송지는 최대 4개까지 등록할 수 있습니다."),
  // 기본 배송지는 항상 1개라 지울 수 없음 (#416). 다른 배송지를 기본으로 바꾼 뒤 삭제
  DEFAULT_ADDRESS_NOT_DELETABLE(HttpStatus.CONFLICT, "기본 배송지는 삭제할 수 없습니다. 다른 배송지를 기본으로 설정한 뒤 삭제해 주세요."),
  // 우편번호나 기본 주소가 실제로 바뀌는데 상세 주소를 보내지 않음 (#416). 옛 상세 주소가 새 주소에 남지 않게
  ADDRESS_DETAIL_REQUIRED(HttpStatus.BAD_REQUEST, "주소를 바꿀 때는 상세 주소도 함께 보내주세요. 비우려면 빈 값으로 보내주세요."),

  // 정상 경로에서는 생기지 않는 판매자 배송지 상태 (빈 목록 · 기본 배송지 0개 · 여러 개). 서버 오류로 멈추고 롤백
  INVALID_SELLER_ADDRESS_STATE(HttpStatus.INTERNAL_SERVER_ERROR, "배송지 정보를 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."),

  // 판매자
  ALREADY_SELLER(HttpStatus.CONFLICT, "이미 판매자로 등록된 회원입니다."),
  SELLER_NOT_FOUND(HttpStatus.NOT_FOUND, "판매자를 찾을 수 없습니다."),
  NOT_SELLER(HttpStatus.FORBIDDEN, "판매자로 등록한 회원만 이용할 수 있습니다."),

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

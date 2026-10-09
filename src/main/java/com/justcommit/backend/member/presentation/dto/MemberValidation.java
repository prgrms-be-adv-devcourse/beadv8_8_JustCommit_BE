package com.justcommit.backend.member.presentation.dto;

// 회원 요청 DTO에서 함께 쓰는 입력 규칙
public final class MemberValidation {

  // @ 앞뒤에 공백·@ 없는 글자가 1자 이상, 도메인에 점(.)이 최소 하나 (예: .com 없는 주소 차단)
  public static final String EMAIL_REGEX = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

  // 8~20자, 영문과 숫자를 각각 1개 이상 포함. 특수문자 !@#$%^&* 허용
  public static final String PASSWORD_REGEX = "^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d!@#$%^&*]{8,20}$";

  // 최소2, 최대8/ 한글·영문·숫자만(특수문자·공백 불가)
  public static final String NICKNAME_REGEX = "^[가-힣A-Za-z0-9]{2,8}$";

  // 하이픈 없이 01로 시작하는 숫자 11자리
  public static final String PHONE_REGEX = "^01\\d{9}$";

  // 은행 코드: 금융결제원 3자리 숫자(예: 004 국민, 088 신한)
  public static final String BANK_CODE_REGEX = "^\\d{3}$";

  // 계좌번호: 하이픈 없이 숫자 10~14자리
  public static final String ACCOUNT_NO_REGEX = "^\\d{10,14}$";

  // 예금주 최대 길이 (account_holder VARCHAR(50))
  public static final int ACCOUNT_HOLDER_MAX_LENGTH = 50;

  // 우편번호(숫자 5자리)
  public static final String ZIPCODE_REGEX = "^\\d{5}$";

  // 배송지 길이(address 테이블 VARCHAR 길이와 동일)
  public static final int RECIPIENT_NAME_MAX_LENGTH = 20;
  public static final int ADDRESS1_MAX_LENGTH = 200;
  public static final int ADDRESS2_MAX_LENGTH = 100;
  public static final int ADDRESS_NAME_MAX_LENGTH = 20;

  private MemberValidation() {
  }
}

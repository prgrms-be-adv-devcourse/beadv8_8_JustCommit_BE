package com.justcommit.backend.member.member.presentation.dto;

import com.justcommit.backend.member.member.application.SocialSignupCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import static com.justcommit.backend.member.presentation.validation.MemberValidation.*;

// 소셜 회원가입 추가정보 (이메일·비밀번호 없음: 이메일은 가입 토큰에서, 비밀번호 사용x)
public record SocialSignupRequest(
        @NotBlank(message = "닉네임을 입력해주세요.")
        @Pattern(regexp = NICKNAME_REGEX, message = "닉네임은 한글, 영문, 숫자 2~8자여야 합니다.")
        String nickname,

        @NotBlank(message = "휴대폰 번호를 입력해주세요.")
        @Pattern(regexp = PHONE_REGEX, message = "휴대폰 번호는 하이픈 없이 숫자 11자리여야 합니다.")
        String phone,

        @NotBlank(message = "은행을 선택해주세요.")
        @Pattern(regexp = BANK_CODE_REGEX, message = "은행 코드는 숫자 3자리여야 합니다.")
        String bankCode,

        @NotBlank(message = "계좌번호를 입력해주세요.")
        @Pattern(regexp = ACCOUNT_NO_REGEX, message = "계좌번호는 하이픈 없이 숫자 10~14자리여야 합니다.")
        String accountNo,

        @NotBlank(message = "예금주를 입력해주세요.")
        @Size(max = ACCOUNT_HOLDER_MAX_LENGTH, message = "예금주는 50자 이하여야 합니다.")
        String accountHolder,

        @NotBlank(message = "받는 사람 이름을 입력해주세요.")
        @Size(max = RECIPIENT_NAME_MAX_LENGTH, message = "받는 사람 이름은 20자 이하여야 합니다.")
        String recipientName,

        @NotBlank(message = "받는 사람 연락처를 입력해주세요.")
        @Pattern(regexp = PHONE_REGEX, message = "받는 사람 연락처는 하이픈 없이 숫자 11자리여야 합니다.")
        String recipientPhone,

        @NotBlank(message = "우편번호를 입력해주세요.")
        @Pattern(regexp = ZIPCODE_REGEX, message = "우편번호는 숫자 5자리여야 합니다.")
        String zipcode,

        @NotBlank(message = "주소를 입력해주세요.")
        @Size(max = ADDRESS1_MAX_LENGTH, message = "주소는 200자 이하여야 합니다.")
        String address1,

        @Size(max = ADDRESS2_MAX_LENGTH, message = "상세 주소는 100자 이하여야 합니다.")
        String address2,

        @Size(max = ADDRESS_NAME_MAX_LENGTH, message = "배송지 이름은 20자 이하여야 합니다.")
        String addressName

) {
  public SocialSignupCommand toCommand() {
    return new SocialSignupCommand(nickname, phone,
            bankCode, accountNo, accountHolder,
            recipientName, recipientPhone, zipcode, address1,
            blankToNull(address2), blankToNull(addressName)
    );
  }

  // 선택 입력값 빈 문자열은 NULL로 통일
  private static String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value;
  }
}

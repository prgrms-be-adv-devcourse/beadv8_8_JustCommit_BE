package com.justcommit.backend.member.address.presentation.dto;

import com.justcommit.backend.member.address.application.AddressCreateCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import static com.justcommit.backend.member.presentation.dto.MemberValidation.*;

// 배송지 등록
public record AddressCreateRequest(
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
        String addressName,

        boolean isDefault // 보내지 않으면 false (기본 배송지로 설정 안 함)
) {
  public AddressCreateCommand toCommand() {
    return new AddressCreateCommand(recipientName, recipientPhone, zipcode, address1,
            blankToNull(address2), blankToNull(addressName), isDefault);
  }

  // 선택 입력값은 빈 문자열("", "  ")을 NULL로 통일
  // TODO: 세미 이후 #101에서 요청 DTO들의 blankToNull 공통화
  private static String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value;
  }
}

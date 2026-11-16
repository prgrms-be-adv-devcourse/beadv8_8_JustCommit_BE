package com.justcommit.backend.member.address.presentation.dto;

import com.justcommit.backend.member.address.application.AddressUpdateCommand;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Objects;
import java.util.stream.Stream;

import static com.justcommit.backend.member.presentation.validation.MemberValidation.*;

// 배송지 부분 수정 (#416): 모든 항목 선택, 보낸 항목만 바꾼다 (보내지 않거나 null이면 그대로)
// 형식 · 길이 규칙은 AddressCreateRequest와 같다. isDefault는 받지 않는다 (기본 변경은 PATCH /{addressId}/default)
public record AddressUpdateRequest(
    // 필수 항목은 보낼 거면 빈 문자열 · 공백만은 불가 (등록의 @NotBlank와 같게, (?s)로 줄바꿈이 있어도 내용이 있으면 통과)
    // @Pattern · @Size는 null이면 검사하지 않음
    @Size(max = RECIPIENT_NAME_MAX_LENGTH, message = "받는 사람 이름은 20자 이하여야 합니다.")
    @Pattern(regexp = NOT_BLANK_REGEX, message = "받는 사람 이름을 입력해주세요.")
    String recipientName,

    @Pattern(regexp = PHONE_REGEX, message = "받는 사람 연락처는 하이픈 없이 숫자 11자리여야 합니다.")
    String recipientPhone,

    @Pattern(regexp = ZIPCODE_REGEX, message = "우편번호는 숫자 5자리여야 합니다.")
    String zipcode,

    @Size(max = ADDRESS1_MAX_LENGTH, message = "주소는 200자 이하여야 합니다.")
    @Pattern(regexp = NOT_BLANK_REGEX, message = "주소를 입력해주세요.")
    String address1,

    // 선택 항목은 빈 문자열을 보내면 지운다
    @Size(max = ADDRESS2_MAX_LENGTH, message = "상세 주소는 100자 이하여야 합니다.")
    String address2,

    @Size(max = ADDRESS_NAME_MAX_LENGTH, message = "배송지 이름은 20자 이하여야 합니다.")
    String addressName
) {

  // 우편번호와 주소는 우편번호 검색 결과라 함께 보내야 한다 (하나만 오면 400)
  @AssertTrue(message = "우편번호와 주소는 함께 입력해주세요.")
  public boolean isZipcodeWithAddress1() {
    return (zipcode == null) == (address1 == null);
  }

  // 바꿀 항목이 하나도 없으면 400
  @AssertTrue(message = "수정할 항목을 입력해주세요.")
  public boolean isAnyFieldPresent() {
    return Stream.of(recipientName, recipientPhone, zipcode, address1, address2, addressName)
        .anyMatch(Objects::nonNull);
  }

  // 빈 문자열을 null로 바꾸지 않는다: 선택 항목의 ""는 "지움"이라 그대로 넘긴다
  public AddressUpdateCommand toCommand() {
    return new AddressUpdateCommand(recipientName, recipientPhone, zipcode, address1, address2, addressName);
  }
}

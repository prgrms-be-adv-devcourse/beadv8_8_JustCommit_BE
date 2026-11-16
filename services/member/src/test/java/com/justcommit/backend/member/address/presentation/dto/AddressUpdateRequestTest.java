package com.justcommit.backend.member.address.presentation.dto;

import com.justcommit.backend.member.address.application.AddressUpdateCommand;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// 배송지 부분 수정 요청 (#416): 모두 선택, 필수 항목 빈 문자열 불가, 우편번호 · 주소 함께, 전부 비면 거절
class AddressUpdateRequestTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  private static AddressUpdateRequest request(String recipientName, String recipientPhone, String zipcode,
                                              String address1, String address2, String addressName) {
    return new AddressUpdateRequest(recipientName, recipientPhone, zipcode, address1, address2, addressName);
  }

  private static Set<String> messages(AddressUpdateRequest request) {
    return validator.validate(request).stream()
            .map(ConstraintViolation::getMessage)
            .collect(java.util.stream.Collectors.toSet());
  }

  @Test
  @DisplayName("한 항목만 보내도 통과 (연락처만 · 배송지 이름만)")
  void singleField() {
    assertThat(validator.validate(request(null, "01098765432", null, null, null, null))).isEmpty();
    assertThat(validator.validate(request(null, null, null, null, null, "회사"))).isEmpty();
  }

  @Test
  @DisplayName("아무것도 보내지 않으면 거절")
  void empty() {
    assertThat(messages(request(null, null, null, null, null, null))).contains("수정할 항목을 입력해주세요.");
  }

  @Test
  @DisplayName("우편번호와 주소는 함께 보내야 함 (하나만 오면 거절, 둘 다 오면 통과)")
  void zipcodeWithAddress1() {
    assertThat(messages(request(null, null, "06236", null, null, null))).contains("우편번호와 주소는 함께 입력해주세요.");
    assertThat(messages(request(null, null, null, "서울 강남구 테헤란로 1", null, null))).contains("우편번호와 주소는 함께 입력해주세요.");
    assertThat(validator.validate(request(null, null, "06236", "서울 강남구 테헤란로 1", null, null))).isEmpty();
  }

  @Test
  @DisplayName("필수 항목(받는 사람 · 주소)은 보낼 거면 빈 문자열 · 공백 불가")
  void requiredBlank() {
    assertThat(messages(request("", null, null, null, null, null))).contains("받는 사람 이름을 입력해주세요.");
    assertThat(messages(request(null, null, "06236", "  ", null, null))).contains("주소를 입력해주세요.");
  }

  @Test
  @DisplayName("선택 항목(상세 주소 · 배송지 이름)의 빈 문자열은 통과하고, 지우기 위해 그대로 넘긴다")
  void optionalBlankKept() {
    AddressUpdateRequest request = request(null, null, null, null, "", "");

    assertThat(validator.validate(request)).isEmpty();
    AddressUpdateCommand command = request.toCommand();
    assertThat(command.address2()).isEmpty();
    assertThat(command.addressName()).isEmpty();
  }

  @Test
  @DisplayName("형식은 등록과 같음: 하이픈 들어간 연락처 · 4자리 우편번호 거절")
  void format() {
    assertThat(validator.validate(request(null, "010-9876-5432", null, null, null, null))).isNotEmpty();
    assertThat(validator.validate(request(null, null, "1234", "서울 강남구 테헤란로 1", null, null))).isNotEmpty();
  }
}

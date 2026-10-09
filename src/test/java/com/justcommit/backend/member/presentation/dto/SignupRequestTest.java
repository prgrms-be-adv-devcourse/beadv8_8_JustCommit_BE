package com.justcommit.backend.member.presentation.dto;

import com.justcommit.backend.member.application.SignupCommand;
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

class SignupRequestTest {

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

  private SignupRequest request(String zipcode, String address2, String addressName) {
    return new SignupRequest("test@gmail.com", "plant1234", "떡볶이", "01012345678",
            "004", "12345678901234", "홍길동",
            "홍길동", "01087654321", zipcode, "서울 강남구 테헤란로 123", address2, addressName);
  }

  @Test
  @DisplayName("선택 입력값(상세 주소·배송지 이름)이 빈 문자열이면 NULL로 바꿔 전달")
  void toCommand_blankToNull() {
    SignupCommand command = request("06236", "", "   ").toCommand();

    assertThat(command.address2()).isNull();
    assertThat(command.addressName()).isNull();
  }

  @Test
  @DisplayName("선택 입력값에 값이 있으면 그대로 전달")
  void toCommand_keepsValue() {
    SignupCommand command = request("06236", "101동 1001호", "집").toCommand();

    assertThat(command.address2()).isEqualTo("101동 1001호");
    assertThat(command.addressName()).isEqualTo("집");
  }

  @Test
  @DisplayName("선택 입력값이 없어도(null) 검증 통과")
  void validate_optionalNull() {
    Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request("06236", null, null));

    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("우편번호가 숫자 5자리가 아니면 zipcode 검증 실패")
  void validate_invalidZipcode() {
    Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request("1234", null, null));

    assertThat(violations)
            .extracting(v -> v.getPropertyPath().toString())
            .containsExactly("zipcode");
  }
}

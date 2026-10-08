package com.justcommit.backend.member.presentation.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class EmailRequestValidationTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @ParameterizedTest
  @ValueSource(strings = {"test@example.com", "a.b+c@gmail.co.kr"})
  @DisplayName("올바른 이메일은 통과")
  void validEmail(String email) {
    assertThat(validator.validate(new EmailSendRequest(email))).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "abc", "eehiuh@gmai", "a b@gmail.com"})
  @DisplayName("빈 값, @ 없음, .com 없음, 공백 포함은 실패")
  void invalidEmail(String email) {
    assertThat(validator.validate(new EmailSendRequest(email))).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"12345", "1234567", "12ab56", ""})
  @DisplayName("인증번호가 숫자 6자리가 아니면 실패")
  void invalidCode(String code) {
    assertThat(validator.validate(new EmailVerifyRequest("test@example.com", code))).isNotEmpty();
  }
}

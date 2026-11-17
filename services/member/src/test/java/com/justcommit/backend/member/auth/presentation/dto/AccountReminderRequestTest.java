package com.justcommit.backend.member.auth.presentation.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

// 가입 계정 안내 요청 형식 (#412)
class AccountReminderRequestTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @ParameterizedTest
  @ValueSource(strings = {"user@example.com"})
  @DisplayName("올바른 이메일은 통과")
  void valid(String email) {
    assertThat(validator.validate(new AccountReminderRequest(email))).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"not-an-email", " "})
  @DisplayName("형식이 틀리거나 빈 이메일은 거절")
  void invalid(String email) {
    assertThat(validator.validate(new AccountReminderRequest(email))).isNotEmpty();
  }
}

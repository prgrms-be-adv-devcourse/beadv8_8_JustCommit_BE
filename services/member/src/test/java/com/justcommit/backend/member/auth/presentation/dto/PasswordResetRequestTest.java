package com.justcommit.backend.member.auth.presentation.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

// 비밀번호 재설정 요청 형식 (#412)
class PasswordResetRequestTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  @DisplayName("토큰과 규칙에 맞는 새 비밀번호면 통과")
  void valid() {
    assertThat(validator.validate(new PasswordResetRequest("reset-token", "newPass123"))).isEmpty();
  }

  @Test
  @DisplayName("재설정 토큰이 비면 거절")
  void blankToken() {
    assertThat(validator.validate(new PasswordResetRequest(" ", "newPass123"))).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"short1", "onlyletters", "12345678", "abcdefghijk1234567890"})
  @DisplayName("새 비밀번호가 규칙(8~20자, 영문 · 숫자 각 1개 이상)에 맞지 않으면 거절")
  void invalidPassword(String newPassword) {
    assertThat(validator.validate(new PasswordResetRequest("reset-token", newPassword))).isNotEmpty();
  }
}

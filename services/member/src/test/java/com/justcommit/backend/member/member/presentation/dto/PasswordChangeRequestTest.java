package com.justcommit.backend.member.member.presentation.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

// 비밀번호 변경 요청 형식 (#411): 새 비밀번호는 가입과 같은 규칙
class PasswordChangeRequestTest {

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

  @Test
  @DisplayName("현재 비밀번호와 규칙에 맞는 새 비밀번호는 통과")
  void valid() {
    assertThat(validator.validate(new PasswordChangeRequest("plant1234", "green5678"))).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "  "})
  @DisplayName("현재 비밀번호가 비어 있으면 거절")
  void blankCurrent(String current) {
    assertThat(validator.validate(new PasswordChangeRequest(current, "green5678"))).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "abc1234", "abcdefgh", "12345678", "abcd1234abcd1234abcd1", "green 5678"})
  @DisplayName("새 비밀번호가 비거나 7자 · 숫자 없음 · 영문 없음 · 21자 · 공백 포함이면 거절")
  void invalidNew(String next) {
    assertThat(validator.validate(new PasswordChangeRequest("plant1234", next))).isNotEmpty();
  }
}

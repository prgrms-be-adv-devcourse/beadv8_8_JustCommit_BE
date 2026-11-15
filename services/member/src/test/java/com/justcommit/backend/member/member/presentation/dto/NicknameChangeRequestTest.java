package com.justcommit.backend.member.member.presentation.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

// 닉네임 변경 요청 형식 (#414): 가입과 같은 규칙, 한글 · 영문 · 숫자 2~8자
class NicknameChangeRequestTest {

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

  @ParameterizedTest
  @ValueSource(strings = {"가나", "abcdefgh", "닉네임12", "Plant7"})
  @DisplayName("한글 · 영문 · 숫자 2~8자는 통과")
  void valid(String nickname) {
    assertThat(validator.validate(new NicknameChangeRequest(nickname))).isEmpty();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"가", "abcdefghi", "닉 네임", "nick!", "  "})
  @DisplayName("빈 값 · 1자 · 9자 · 공백 · 특수문자는 거절")
  void invalid(String nickname) {
    assertThat(validator.validate(new NicknameChangeRequest(nickname))).isNotEmpty();
  }
}

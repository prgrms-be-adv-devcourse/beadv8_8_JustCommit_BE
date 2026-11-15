package com.justcommit.backend.member.seller.presentation.dto;

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

// 판매자 소개글 수정 요청 (#417): intro 필수 · 최대 500자, 빈 문자열 · 공백만 있으면 NULL
class SellerIntroChangeRequestTest {

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
  @DisplayName("500자는 통과, 501자는 거절")
  void maxLength() {
    assertThat(validator.validate(new SellerIntroChangeRequest("가".repeat(500)))).isEmpty();
    assertThat(validator.validate(new SellerIntroChangeRequest("가".repeat(501)))).isNotEmpty();
  }

  @Test
  @DisplayName("intro가 없으면({} · null) 거절")
  void nullRejected() {
    assertThat(validator.validate(new SellerIntroChangeRequest(null))).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "   "})
  @DisplayName("빈 문자열 · 공백만 있으면 통과하고 NULL로 바뀐다 (소개글 삭제)")
  void blankBecomesNull(String intro) {
    SellerIntroChangeRequest request = new SellerIntroChangeRequest(intro);

    assertThat(validator.validate(request)).isEmpty();
    assertThat(request.introOrNull()).isNull();
  }

  @Test
  @DisplayName("일반 문자열은 그대로")
  void textKept() {
    assertThat(new SellerIntroChangeRequest("식물 팔아요").introOrNull()).isEqualTo("식물 팔아요");
  }
}

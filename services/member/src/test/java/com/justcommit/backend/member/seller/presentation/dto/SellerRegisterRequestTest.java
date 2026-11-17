package com.justcommit.backend.member.seller.presentation.dto;

import com.justcommit.backend.member.seller.application.SellerRegisterCommand;
import com.justcommit.backend.member.seller.domain.SellerReviewConsentPolicy;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// 판매자 등록 요청 (#418): 상품 검수 동의 필수(누락 · false 거절), 동의 버전 필수, 소개글은 선택 · 최대 500자
class SellerRegisterRequestTest {

  private static final String CONSENT_MESSAGE = "상품 검수를 위한 외부 AI 서비스 이용 및 국외 이전에 동의해 주세요.";
  private static final String VERSION_MESSAGE = "동의한 약관 버전이 필요합니다.";
  private static final String INTRO_MESSAGE = "소개글은 500자 이하여야 합니다.";

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
  @DisplayName("동의하고 현재 버전을 보내면 통과")
  void valid() {
    assertThat(validator.validate(request("식물 팔아요", true, SellerReviewConsentPolicy.VERSION))).isEmpty();
  }

  @Test
  @DisplayName("동의 여부가 없으면 거절")
  void consentMissing() {
    assertOnlyViolation(request(null, null, SellerReviewConsentPolicy.VERSION), "reviewConsentAgreed", CONSENT_MESSAGE);
  }

  @Test
  @DisplayName("동의하지 않으면(false) 거절")
  void consentFalse() {
    assertOnlyViolation(request(null, false, SellerReviewConsentPolicy.VERSION), "reviewConsentAgreed", CONSENT_MESSAGE);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "   "})
  @DisplayName("동의 버전이 없거나 빈 값이면 거절")
  void versionBlank(String version) {
    assertOnlyViolation(request(null, true, version), "reviewConsentVersion", VERSION_MESSAGE);
  }

  @Test
  @DisplayName("소개글은 500자까지 통과, 501자는 거절")
  void introMaxLength() {
    assertThat(validator.validate(request("가".repeat(500), true, SellerReviewConsentPolicy.VERSION))).isEmpty();
    assertOnlyViolation(request("가".repeat(501), true, SellerReviewConsentPolicy.VERSION), "intro", INTRO_MESSAGE);
  }

  @Test
  @DisplayName("Command로 바꿀 때 공백 소개글은 NULL, 동의 버전은 그대로")
  void toCommand() {
    SellerRegisterCommand command = request("  ", true, SellerReviewConsentPolicy.VERSION).toCommand(1L);

    assertThat(command.memberId()).isEqualTo(1L);
    assertThat(command.intro()).isNull();
    assertThat(command.reviewConsentVersion()).isEqualTo(SellerReviewConsentPolicy.VERSION);
  }

  // 확인하려는 필드 하나만 틀린 요청을 만들기 위한 도우미
  private static SellerRegisterRequest request(String intro, Boolean agreed, String version) {
    return new SellerRegisterRequest(intro, agreed, version);
  }

  // 위반이 정확히 하나이고, 기대한 필드 · 메시지인지 확인
  private static void assertOnlyViolation(SellerRegisterRequest request, String field, String message) {
    Set<ConstraintViolation<SellerRegisterRequest>> violations = validator.validate(request);
    assertThat(violations).hasSize(1);
    ConstraintViolation<SellerRegisterRequest> violation = violations.iterator().next();
    assertThat(violation.getPropertyPath().toString()).isEqualTo(field);
    assertThat(violation.getMessage()).isEqualTo(message);
  }
}

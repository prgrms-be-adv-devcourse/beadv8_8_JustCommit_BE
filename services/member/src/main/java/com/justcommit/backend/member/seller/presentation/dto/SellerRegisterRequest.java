package com.justcommit.backend.member.seller.presentation.dto;

import com.justcommit.backend.member.presentation.validation.MemberValidation;
import com.justcommit.backend.member.seller.application.SellerRegisterCommand;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SellerRegisterRequest(
        @Size(max = MemberValidation.SELLER_INTRO_MAX_LENGTH, message = "소개글은 500자 이하여야 합니다.")
        String intro,

        // 상품 검수(외부 AI 이용 · 국외 이전) 필수 동의 (#418). 누락 · false 모두 400
        // Boolean: 원시 타입이면 누락이 false로 채워져 구분되지 않음
        @NotNull(message = "상품 검수를 위한 외부 AI 서비스 이용 및 국외 이전에 동의해 주세요.")
        @AssertTrue(message = "상품 검수를 위한 외부 AI 서비스 이용 및 국외 이전에 동의해 주세요.")
        Boolean reviewConsentAgreed,

        // 화면에서 보여 준 동의 문구의 버전. 현재 버전과 다르면 Service에서 409
        @NotBlank(message = "동의한 약관 버전이 필요합니다.")
        String reviewConsentVersion
) {

  // 동의 여부는 검증을 통과하면 항상 true라 버전만 넘김
  public SellerRegisterCommand toCommand(Long memberId) {
    return new SellerRegisterCommand(memberId, blankToNull(intro), reviewConsentVersion);
  }

  // 소개글은 선택값(빈 문자열·공백 입력 -> NULL)
  private static String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value;
  }
}

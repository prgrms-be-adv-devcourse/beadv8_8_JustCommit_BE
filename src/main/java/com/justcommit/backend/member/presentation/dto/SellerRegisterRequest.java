package com.justcommit.backend.member.presentation.dto;

import com.justcommit.backend.member.application.SellerRegisterCommand;
import jakarta.validation.constraints.Size;

public record SellerRegisterRequest(
        @Size(max = MemberValidation.SELLER_INTRO_MAX_LENGTH) String intro
) {

  public SellerRegisterCommand toCommand(Long memberId) {
    return new SellerRegisterCommand(memberId, blankToNull(intro));
  }

  // 소개글은 선택값(빈 문자열·공백 입력 -> NULL)
  private static String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value;
  }
}

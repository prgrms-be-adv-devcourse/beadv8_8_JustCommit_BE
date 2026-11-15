package com.justcommit.backend.member.seller.presentation.dto;

import com.justcommit.backend.member.presentation.validation.MemberValidation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 판매자 소개글 수정 요청 (#417).
 * intro는 필수: {} · {"intro":null}은 400. 지우려면 빈 문자열("")을 보낸다 (공백만 있어도 삭제).
 */
public record SellerIntroChangeRequest(
    @NotNull(message = "소개글을 입력해 주세요. 지우려면 빈 문자열을 보내 주세요.")
    @Size(max = MemberValidation.SELLER_INTRO_MAX_LENGTH, message = "소개글은 500자 이하여야 합니다.")
    String intro
) {

  // 빈 문자열 · 공백만 있으면 NULL (판매자 등록 요청과 같은 규칙)
  public String introOrNull() {
    return intro.isBlank() ? null : intro;
  }
}

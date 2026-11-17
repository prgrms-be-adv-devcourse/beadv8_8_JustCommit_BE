package com.justcommit.backend.member.seller.presentation.dto;

import com.justcommit.backend.member.seller.domain.SellerReviewConsentPolicy;

// 판매자 등록 화면에 보여 줄 현재 동의 정책 (#418). content는 일반 텍스트(줄바꿈 포함)
public record SellerReviewConsentPolicyResponse(
        String version,
        String title,
        String content
) {

  public static SellerReviewConsentPolicyResponse current() {
    return new SellerReviewConsentPolicyResponse(
        SellerReviewConsentPolicy.VERSION,
        SellerReviewConsentPolicy.TITLE,
        SellerReviewConsentPolicy.CONTENT);
  }
}

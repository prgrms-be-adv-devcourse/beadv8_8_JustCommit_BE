package com.justcommit.backend.member.seller.domain;

// 판매자 등록 때 받는 상품 검수(외부 AI 이용 · 국외 이전) 동의의 현재 버전과 문구
// 문구를 바꾸면 VERSION을 올리고, 이전 문구는 같은 PR에서 docs/policies/seller-review-consent/{version}.md로 옮긴다
// TODO: 운영 전 + 국외 이전 고지 요건 확인 후 v1 문구 확정 (그 전까지는 v1을 그대로 고쳐도 됨)
public final class SellerReviewConsentPolicy {

  public static final String VERSION = "v1";

  public static final String TITLE = "상품 검수를 위한 외부 AI 서비스 이용 및 국외 이전 동의";

  // 일반 텍스트 (프론트는 white-space: pre-line으로 줄바꿈 그대로 표시)
  public static final String CONTENT = """
      상품의 안전성과 판매 적합성을 확인하기 위해, 상품 등록 · 수정 과정에서 검수가 필요한 정보를 외부 AI 서비스로 전송합니다.

      1. 전송 정보와 목적
      - OpenAI: 상품 사진 · 제목 · 설명 / 유해 콘텐츠 여부 검수
      - Anthropic: 상품 사진 / 식물 여부 · 품종 · 판매 형태 검수
      판매자 이름, 연락처, 계좌정보 등 회원정보는 검수 요청에 별도로 포함하지 않습니다.

      2. 국외 이전
      - 이전받는 업체: OpenAI, Anthropic
      - 이전 국가: 미국 등 해외 처리 시설 (현재 확인된 설정상 처리 지역이 특정 국가로 고정되어 있지 않음)
      - 이전 시기와 방법: 상품 등록 · 수정 과정에서 검수가 필요한 경우 암호화된 통신으로 전송
      - 보유 기간(OpenAI): Moderation 검수 요청 데이터를 보관하지 않음
      - 보유 기간(Anthropic): 일반적으로 받은 날부터 30일 이내 삭제, 이용정책 위반으로 판정된 경우 최대 2년 보관

      3. AI 학습 여부
      현재 OpenAI와 Anthropic의 API 정책 및 서비스 설정에 따라, 검수 목적으로 전송된 데이터는 AI 모델 학습에 사용되지 않습니다. 리플랜트도 판매자가 등록한 상품 사진을 자체 AI 모델 학습에 사용하지 않습니다.

      4. 동의 거부
      동의를 거부할 수 있습니다. 다만 상품 검수를 진행할 수 없어 판매자 등록이 제한됩니다.""";

  private SellerReviewConsentPolicy() {
  }

  // 요청 버전이 현재 버전인지 (null이면 false)
  public static boolean isCurrent(String version) {
    return VERSION.equals(version);
  }
}

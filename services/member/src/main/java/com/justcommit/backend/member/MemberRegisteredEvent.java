package com.justcommit.backend.member;

// 회원 생성 이벤트 (다른 모듈이 구독해서 장바구니·지갑 등을 만듦)
public record MemberRegisteredEvent(Long memberId, boolean systemAccount) {

  // 카프카 토픽 이름 (구독할 때 @KafkaListener(topics = MemberRegisteredEvent.TOPIC))
  public static final String TOPIC = "MemberRegisteredEvent";
}

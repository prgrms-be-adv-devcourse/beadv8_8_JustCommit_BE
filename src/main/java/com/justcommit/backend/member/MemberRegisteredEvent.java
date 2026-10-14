package com.justcommit.backend.member;

// 회원 생성 이벤트 (다른 모듈이 구독해서 장바구니·지갑 등을 만듦)
public record MemberRegisteredEvent(
        Long memberId,
        boolean systemAccount // 시스템 계정(홀딩·매출)이면 true. 장바구니처럼 사람용 데이터는 건너뛰면 됨
) {
}

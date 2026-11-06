package com.justcommit.backend.product.client.member;

// product가 쓰는 회원 조회 (회원 복제본이 생기기 전까지 임시 구현 UnavailableMemberClient)
public interface MemberQuery {

    // 판매자로 등록된 회원인지 (회원이 없으면 false)
    boolean isSeller(Long memberId);

    // 판매자 닉네임
    String getNickname(Long memberId);
}

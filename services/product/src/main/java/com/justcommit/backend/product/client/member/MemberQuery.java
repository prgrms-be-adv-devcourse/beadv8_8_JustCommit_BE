package com.justcommit.backend.product.client.member;

import java.util.Collection;
import java.util.Map;

// product가 쓰는 회원 조회. 구현은 회원 복제본을 읽는 MemberReplicaQuery
public interface MemberQuery {

    // 판매자로 등록된 회원인지 (복제본이 없거나 탈퇴했으면 false)
    boolean isSeller(Long memberId);

    // 판매자 닉네임 (탈퇴했으면 "탈퇴한 회원", 복제본이 없으면 null)
    String getNickname(Long memberId);

    // 여러 회원의 닉네임을 한 번에 (목록용). 복제본이 없는 회원은 Map에서 빠짐, 탈퇴했으면 "탈퇴한 회원"
    Map<Long, String> getNicknames(Collection<Long> memberIds);
}

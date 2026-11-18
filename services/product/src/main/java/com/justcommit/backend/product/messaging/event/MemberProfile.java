package com.justcommit.backend.product.messaging.event;

import com.justcommit.backend.product.member.domain.MemberRole;
import java.util.Objects;

// member.events.v1의 MemberCreated · MemberUpdated payload (두 이벤트의 칸이 같음, 바뀐 뒤 전체 값)
// 칸은 member #350과 합의: seller는 판매자 등록 여부 (ADMIN도 판매자가 될 수 있어 role과 따로 옴)
public record MemberProfile(Long memberId, String nickname, MemberRole role, boolean seller) {

    public MemberProfile {
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(nickname, "nickname");
        Objects.requireNonNull(role, "role");
    }
}

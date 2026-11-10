package com.justcommit.backend.product.messaging.event;

import com.justcommit.backend.product.member.domain.MemberRole;
import java.util.Objects;

// member.events.v1의 MemberCreated · MemberUpdated payload (두 이벤트의 칸이 같음)
// shortcut: 칸 이름은 member 담당과 확정 전 제안안 (#227 · #339)
public record MemberProfile(Long memberId, String nickname, MemberRole role) {

    public MemberProfile {
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(nickname, "nickname");
        Objects.requireNonNull(role, "role");
    }
}

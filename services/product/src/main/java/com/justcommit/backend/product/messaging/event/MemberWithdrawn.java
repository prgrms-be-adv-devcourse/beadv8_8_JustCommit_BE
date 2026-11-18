package com.justcommit.backend.product.messaging.event;

import java.util.Objects;

// member.events.v1의 MemberWithdrawn payload
public record MemberWithdrawn(Long memberId) {

    public MemberWithdrawn {
        Objects.requireNonNull(memberId, "memberId");
    }
}

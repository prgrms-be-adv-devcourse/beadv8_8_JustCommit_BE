package com.justcommit.backend.market.client.member;

import java.util.Collection;
import java.util.Map;

public interface MemberQuery {

    MemberAddress getDefaultAddress(Long memberId);

    Map<Long, String> getNicknames(Collection<Long> memberIds);

    // 복제본에 탈퇴 표시가 있는지 (복제본이 아직 없으면 false)
    boolean isWithdrawn(Long memberId);
}

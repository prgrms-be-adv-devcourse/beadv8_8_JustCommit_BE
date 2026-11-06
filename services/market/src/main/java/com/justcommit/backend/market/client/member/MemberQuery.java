package com.justcommit.backend.market.client.member;

import java.util.Collection;
import java.util.Map;

public interface MemberQuery {

    MemberAddress getDefaultAddress(Long memberId);

    Map<Long, String> getNicknames(Collection<Long> memberIds);
}

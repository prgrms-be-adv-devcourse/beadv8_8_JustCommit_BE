package com.justcommit.backend.market.client.member;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import java.util.Collection;
import java.util.Map;
import org.springframework.stereotype.Component;

// shortcut: 회원 복제본이 생기기 전 임시 구현이라 호출하면 503, #275에서 member_replica · 판매자 배송지 복제본 조회로 교체
@Component
class UnavailableMemberClient implements MemberQuery {

    @Override
    public MemberAddress getDefaultAddress(Long memberId) {
        throw unavailable();
    }

    @Override
    public Map<Long, String> getNicknames(Collection<Long> memberIds) {
        throw unavailable();
    }

    private static BusinessException unavailable() {
        return new BusinessException(CommonErrorCode.INTERNAL_API_UNAVAILABLE);
    }
}

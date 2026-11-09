package com.justcommit.backend.product.client.member;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import org.springframework.stereotype.Component;

// shortcut: 회원 복제본이 생기기 전 임시 구현이라 호출하면 503, #236에서 member_replica 조회로 교체
@Component
class UnavailableMemberClient implements MemberQuery {

    @Override
    public boolean isSeller(Long memberId) {
        throw unavailable();
    }

    @Override
    public String getNickname(Long memberId) {
        throw unavailable();
    }

    private static BusinessException unavailable() {
        return new BusinessException(CommonErrorCode.INTERNAL_API_UNAVAILABLE);
    }
}

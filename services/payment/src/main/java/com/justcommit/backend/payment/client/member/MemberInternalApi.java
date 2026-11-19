package com.justcommit.backend.payment.client.member;

import com.justcommit.backend.common.response.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

// member 내부 API (docs/reference/internal-apis.md). 그룹 internal-member, 타임아웃은 내부 호출 기본값
@HttpExchange
public interface MemberInternalApi {

    // 응답 본문에 계좌번호 원문이 있으므로 응답 본문을 로그로 남기지 않는다
    @GetExchange("/internal/v1/members/{memberId}/account")
    ApiResponse<MemberAccount> getAccount(@PathVariable Long memberId);
}

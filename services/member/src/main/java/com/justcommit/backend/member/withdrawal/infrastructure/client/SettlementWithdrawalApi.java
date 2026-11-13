package com.justcommit.backend.member.withdrawal.infrastructure.client;

import com.justcommit.backend.common.response.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

// settlement 탈퇴 확인 내부 API (미완료 정산 PENDING · FAILED)
// X-Internal-Key 헤더는 여기서 넣지 않음: common-web이 internal-api.groups에 등록한 그룹의 요청에 자동으로 붙임
@HttpExchange("/internal/v1/settlements/members")
public interface SettlementWithdrawalApi {

  // authorization: 병렬 호출 전에 확보한 사용자 Authorization을 그대로 넘김 (null이면 보내지 않음)
  @GetExchange("/{memberId}/withdrawalCheck")
  ApiResponse<WithdrawalCheckResponse> check(
      @PathVariable Long memberId,
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization);
}

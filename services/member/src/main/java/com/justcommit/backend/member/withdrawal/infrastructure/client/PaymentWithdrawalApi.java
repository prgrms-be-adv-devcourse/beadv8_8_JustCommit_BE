package com.justcommit.backend.member.withdrawal.infrastructure.client;

import com.justcommit.backend.common.response.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

// payment 탈퇴 확인 내부 API (잔액 · 진행 중 거래 · 미수금)
// X-Internal-Key 헤더는 여기서 넣지 않음: common-web이 internal-api.groups에 등록한 그룹의 요청에 자동으로 붙임
@HttpExchange("/internal/v1/payments/members")
public interface PaymentWithdrawalApi {

  @GetExchange("/{memberId}/withdrawalCheck")
  ApiResponse<WithdrawalCheckResponse> check(@PathVariable Long memberId);
}

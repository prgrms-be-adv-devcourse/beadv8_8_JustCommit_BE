package com.justcommit.backend.member.withdrawal.presentation.dto;

import com.justcommit.backend.member.withdrawal.application.WithdrawalCheckResult;

import java.util.List;
import java.util.Map;

// 탈퇴 가능 여부 (화면 안내용). status: ALLOWED · BLOCKED, blockers: 서비스별 차단 사유 (ALLOWED면 빈 배열)
// 내부 API 응답(infrastructure.client.WithdrawalCheckResponse)과 구분하려고 이름을 다르게 둔다
public record WithdrawalEligibilityResponse(String status, List<Blocker> blockers) {

  public record Blocker(String service, List<String> reasons, Map<String, Object> details) {
  }

  public static WithdrawalEligibilityResponse from(WithdrawalCheckResult result) {
    List<Blocker> blockers = result.blockers().stream()
        .map(b -> new Blocker(b.service(), b.reasons(), b.details()))
        .toList();
    return new WithdrawalEligibilityResponse(result.status().name(), blockers);
  }
}

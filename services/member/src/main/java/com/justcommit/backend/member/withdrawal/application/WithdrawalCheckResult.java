package com.justcommit.backend.member.withdrawal.application;

import java.util.List;
import java.util.Map;

// 탈퇴 가능 여부 확인 결과. UNAVAILABLE이어도 정상 응답에서 받은 차단 사유는 blockers에 남긴다
public record WithdrawalCheckResult(
    WithdrawalCheckStatus status,
    List<ServiceBlocker> blockers,
    List<String> failedServices
) {
  // 서비스 한 곳의 차단 사유 (details는 표시용, 받은 그대로)
  public record ServiceBlocker(String service, List<String> reasons, Map<String, Object> details) {
  }

  public boolean isAllowed() {
    return status == WithdrawalCheckStatus.ALLOWED;
  }
}

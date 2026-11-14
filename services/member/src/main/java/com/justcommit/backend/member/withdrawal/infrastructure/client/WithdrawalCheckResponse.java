package com.justcommit.backend.member.withdrawal.infrastructure.client;

import java.util.List;
import java.util.Map;

// market · payment · settlement 탈퇴 확인 응답 (세 서비스 공통 규격)/ 받은 값을 그대로 보관
// 누락된 칸은 null로 남겨 정상적인 false · 빈 목록과 구분한다 (형식 검사는 탈퇴 확인 서비스에서)
public record WithdrawalCheckResponse(
    Boolean hasWithdrawalBlocker,
    List<String> blockingReasons,
    Map<String, Object> details
) {
}

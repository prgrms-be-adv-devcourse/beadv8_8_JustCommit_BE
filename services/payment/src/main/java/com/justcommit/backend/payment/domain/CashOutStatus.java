package com.justcommit.backend.payment.domain;

// 인출 건 상태. 지급 재시도, 확정 실패 복원, 확인 필요 상태는 #389에서 추가한다
public enum CashOutStatus {
    // 잔액 차감 후 지급 전, 또는 지급 결과를 아직 반영하지 못함
    PENDING,
    // 은행 지급 완료
    COMPLETED
}

package com.justcommit.backend.payment.infrastructure.bank;

// 은행 지급이 성공으로 끝나지 않음 (확정 실패, 요청 오류, 결과 불명 모두). 메시지에 계좌번호를 넣지 않는다
public class BankTransferException extends RuntimeException {

    public BankTransferException(String message) {
        super(message);
    }
}

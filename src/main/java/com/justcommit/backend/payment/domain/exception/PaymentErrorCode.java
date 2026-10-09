package com.justcommit.backend.payment.domain.exception;

import com.justcommit.backend.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {
    CHARGE_NOT_FOUND(HttpStatus.NOT_FOUND, "충전 요청을 찾을 수 없습니다."),
    CHARGE_ALREADY_PROCESSED(HttpStatus.CONFLICT, "이미 처리된 충전 요청입니다."),
    CHARGE_INFO_MISMATCH(HttpStatus.BAD_REQUEST, "충전 요청 정보가 일치하지 않습니다."),
    PG_CONFIRM_FAILED(HttpStatus.BAD_GATEWAY, "결제 승인에 실패했습니다."),
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "금액은 0보다 커야 합니다."),
    INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "예치금 잔액이 부족합니다."),
    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "지갑을 찾을 수 없습니다."),
    INVALID_CHARGE_AMOUNT(HttpStatus.BAD_REQUEST, "충전 금액이 올바르지 않습니다.");

    private final HttpStatus status;
    private final String message;
}

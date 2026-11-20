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
    INVALID_CHARGE_AMOUNT(HttpStatus.BAD_REQUEST, "충전 금액은 10,000원 이상, 10,000원 단위입니다."),
    REFUND_INFO_MISMATCH(HttpStatus.CONFLICT, "기존 환불 내역과 요청 정보가 일치하지 않습니다."),
    REFUND_REFERENCE_CONFLICT(HttpStatus.CONFLICT, "이미 다른 구매자에게 처리된 환불 건입니다."),
    SHIPPING_FEE_ALREADY_COLLECTED(HttpStatus.CONFLICT,"이미 수납된 배송비는 환불할 수 없습니다."),
    CHARGE_PROCESSING(HttpStatus.CONFLICT,"충전 승인 처리 중입니다."),

    // 인출
    CASH_OUT_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "인출할 계좌를 찾을 수 없습니다."),
    ACCOUNT_LOOKUP_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "계좌 정보를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."),
    CASH_OUT_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "인출 금액이 예치금 잔액보다 큽니다."),
    RECEIVABLE_REMAINING(HttpStatus.CONFLICT, "미수금이 남아 있어 인출할 수 없습니다."),
    INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "Idempotency-Key는 1자 이상 64자 이하입니다."),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "같은 Idempotency-Key로 다른 요청이 이미 처리되었습니다.");

    private final HttpStatus status;
    private final String message;
}

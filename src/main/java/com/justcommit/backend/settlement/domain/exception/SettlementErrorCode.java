package com.justcommit.backend.settlement.domain.exception;

import com.justcommit.backend.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SettlementErrorCode implements ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "정산 요청 정보가 올바르지 않습니다."),
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "판매액은 0보다 커야 합니다.");

    private final HttpStatus status;
    private final String message;
}

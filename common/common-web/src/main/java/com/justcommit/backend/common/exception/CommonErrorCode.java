package com.justcommit.backend.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    BAD_REQUEST_DATA(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    INVALID_FIELD_ERROR(HttpStatus.BAD_REQUEST, "요청 필드 값이 유효하지 않습니다."),
    MISSING_PARAMETER(HttpStatus.BAD_REQUEST, "필수 요청 파라미터가 누락되었습니다."),
    MISSING_HEADER(HttpStatus.BAD_REQUEST, "필수 요청 헤더가 누락되었습니다."),
    TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "요청 값 타입이 올바르지 않습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    INTERNAL_API_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    INTERNAL_API_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "처리 결과를 확인하지 못했습니다. 잠시 후 처리 내역을 확인해 주세요.");

    private final HttpStatus status;
    private final String message;
}
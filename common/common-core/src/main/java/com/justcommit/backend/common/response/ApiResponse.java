package com.justcommit.backend.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.justcommit.backend.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(int status, String code, String message, T data) {

    // 성공 (데이터 있음)
    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(HttpStatus.OK.value(), null, message, data);
    }

    // 성공 (데이터 없음: 삭제, 숨김 처리 등)
    public static ApiResponse<Void> ok(String message) {
        return new ApiResponse<>(HttpStatus.OK.value(), null, message, null);
    }

    // 생성 성공 (상품 등록 등)
    public static <T> ApiResponse<T> created(String message, T data) {
        return new ApiResponse<>(HttpStatus.CREATED.value(), null, message, data);
    }

    // 실패: 에러 코드의 기본 메시지 사용
    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return error(errorCode, errorCode.getMessage());
    }

    // 실패: 상황에 맞는 메시지로 덮어쓰기
    public static ApiResponse<Void> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getStatus().value(), errorCode.name(), message, null);
    }
}
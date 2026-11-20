package com.justcommit.backend.ai.common;

import com.justcommit.backend.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AiErrorCode implements ErrorCode {

    INVALID_SEARCH_QUERY(HttpStatus.BAD_REQUEST, "검색 문장은 2자 이상 100자 이하로 입력해 주세요."),
    INVALID_SEARCH_SIZE(HttpStatus.BAD_REQUEST, "검색 개수는 1 이상 10 이하로 입력해 주세요."),
    SEMANTIC_SEARCH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 문장으로 찾기를 쓸 수 없습니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String message;
}

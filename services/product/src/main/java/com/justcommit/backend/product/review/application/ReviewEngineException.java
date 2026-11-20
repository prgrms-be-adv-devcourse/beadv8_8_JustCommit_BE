package com.justcommit.backend.product.review.application;

// 외부 검수 API(OpenAI Moderation · Claude Haiku)가 재시도 후에도 실패했거나 응답을 해석하지 못함 → 관리자 확인
// retryable=false: 외부 API가 4xx(429 제외)로 답함. 요청 자체가 잘못돼 다시 보내도 같은 결과라 엔진 오류 재처리 없이 바로 관리자 확인 (#594)
public class ReviewEngineException extends RuntimeException {

    private final boolean retryable;

    public ReviewEngineException(String message) {
        this(message, null, true);
    }

    public ReviewEngineException(String message, Throwable cause) {
        this(message, cause, true);
    }

    private ReviewEngineException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    // 외부 API가 4xx(429 제외)로 답한 경우
    public static ReviewEngineException rejected(String message, Throwable cause) {
        return new ReviewEngineException(message, cause, false);
    }

    // 429는 잠깐 뒤 다시 하면 될 수 있어 재처리 대상
    public static boolean isClientError(int status) {
        return status >= 400 && status < 500 && status != 429;
    }

    public boolean isRetryable() {
        return retryable;
    }
}

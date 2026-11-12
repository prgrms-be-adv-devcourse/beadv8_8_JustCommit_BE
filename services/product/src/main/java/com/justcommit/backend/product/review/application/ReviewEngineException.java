package com.justcommit.backend.product.review.application;

// 외부 검수 API(OpenAI Moderation · Claude Haiku)가 재시도 후에도 실패했거나 응답을 해석하지 못함 → 관리자 확인
public class ReviewEngineException extends RuntimeException {

    public ReviewEngineException(String message) {
        super(message);
    }

    public ReviewEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}

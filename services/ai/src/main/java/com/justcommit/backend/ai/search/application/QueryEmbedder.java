package com.justcommit.backend.ai.search.application;

// 검색 문장 → 벡터. 실패(시간 초과 · 연결 실패 · API 오류)는 QueryEmbeddingException으로 던진다
public interface QueryEmbedder {

    float[] embed(String query);

    class QueryEmbeddingException extends RuntimeException {
        public QueryEmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

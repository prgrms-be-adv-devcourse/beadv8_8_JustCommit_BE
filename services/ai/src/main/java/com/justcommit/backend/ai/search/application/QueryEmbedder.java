package com.justcommit.backend.ai.search.application;

// 검색 문장 → 벡터. 실패(시간 초과 · 연결 실패 · API 오류)는 QueryEmbeddingException으로 던진다
public interface QueryEmbedder {

    // 비로그인 품종 의미 검색이 쓰는 질문 임베딩 캐시 범위
    String SPECIES_SEARCH_SCOPE = "species-search";

    // 챗봇은 회원마다 따로 (#636 리뷰): 공개 검색의 응답 시간으로 남의 챗봇 질문을 짐작하지 못하게
    static String chatbotScope(long memberId) {
        return "chatbot:" + memberId;
    }

    // cacheScope: 같은 범위 안에서만 같은 질문의 벡터를 다시 씀 (질문 임베딩 캐시)
    float[] embed(String query, String cacheScope);

    class QueryEmbeddingException extends RuntimeException {
        public QueryEmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

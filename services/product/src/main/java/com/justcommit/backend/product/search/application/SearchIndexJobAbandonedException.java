package com.justcommit.backend.product.search.application;

// 실행 중인 색인 작업이 이미 끝난 것으로 기록돼 있음 (하트비트가 끊겨 죽은 작업으로 실패 처리된 뒤 늦게 깨어난 실행, #563)
public class SearchIndexJobAbandonedException extends RuntimeException {

    public SearchIndexJobAbandonedException(Long jobId) {
        super("상품 검색 색인 작업 " + jobId + "는 이미 끝난 것으로 기록돼 있어 멈춤 (하트비트가 끊겨 실패 처리됨)");
    }
}

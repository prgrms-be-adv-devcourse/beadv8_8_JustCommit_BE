package com.justcommit.backend.product.search.application;

// OpenSearch로 검색할 수 없음 (시간 초과 · 연결 실패 · 인덱스 없음 등). 서비스가 잡아 DB LIKE 검색으로 대신한다
public class ProductSearchUnavailableException extends RuntimeException {

    public ProductSearchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

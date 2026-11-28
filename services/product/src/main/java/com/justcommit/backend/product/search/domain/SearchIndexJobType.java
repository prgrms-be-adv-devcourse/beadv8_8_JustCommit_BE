package com.justcommit.backend.product.search.domain;

// 상품 검색 색인 작업 종류 (#563)
public enum SearchIndexJobType {
    // 전체 색인: alias에 DB 전체를 다시 씀 (요청 스레드에서 바로 실행하고 결과를 응답)
    REINDEX_ALL,
    // 무중단 재색인: 새 인덱스를 채우고 alias를 옮김 (요청은 작업만 등록하고 202, 별도 실행자에서 실행)
    REBUILD
}

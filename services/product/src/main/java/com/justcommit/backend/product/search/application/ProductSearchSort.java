package com.justcommit.backend.product.search.application;

// 상품 목록 정렬. 관련도는 키워드가 있을 때만 의미가 있고, 동점이면 최신순 (#486)
public enum ProductSearchSort {
    RELEVANCE,
    LATEST,
    PRICE_ASC,
    PRICE_DESC
}

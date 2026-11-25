package com.justcommit.backend.product.search.application;

import com.justcommit.backend.product.ProductStatus;
import java.math.BigDecimal;
import java.util.List;

/**
 * 키워드로 상품을 찾는 쪽 (구현: search/infrastructure/OpenSearchProductSearch).
 *
 * <p>결과는 상품 id와 전체 개수만 돌려준다. 화면에 보여 줄 값(사진 · 가격 등)은 서비스가 DB에서 다시 읽는다:
 * 응답 형식을 DB 조회와 똑같이 두고, 색인이 몇 초 늦어도 화면에는 DB 최신 값이 나가게.
 *
 * <p>시간 초과 · 연결 실패 · OpenSearch 오류면 {@link ProductSearchUnavailableException}을 던진다 (서비스가 LIKE로 대신).
 */
public interface ProductKeywordSearch {

    Result search(Condition condition, ProductSearchSort sort, int page, int size);

    /**
     * 검색 조건. statuses는 서비스가 노출 규칙 · onSale · status로 이미 좁힌 목록 (숨김은 항상 제외).
     * 나머지 null인 칸은 거르지 않는다.
     */
    record Condition(
            String keyword,
            List<ProductStatus> statuses,
            Long speciesId,
            String category,
            String light,
            String watering,
            String difficulty,
            BigDecimal minPrice,
            BigDecimal maxPrice
    ) {
    }

    // productIds는 정렬된 순서 그대로, total은 조건에 맞는 전체 개수,
    // tookMillis는 OpenSearch 응답의 took(OpenSearch 안에서 검색에 쓴 시간, 모르면 음수. 단계별 시간 #634)
    record Result(List<Long> productIds, long total, long tookMillis) {

        public static final long UNKNOWN_TOOK = -1;

        public Result(List<Long> productIds, long total) {
            this(productIds, total, UNKNOWN_TOOK);
        }
    }
}

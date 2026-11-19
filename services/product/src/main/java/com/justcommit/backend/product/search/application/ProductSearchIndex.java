package com.justcommit.backend.product.search.application;

import java.util.List;

/**
 * 상품 검색 인덱스에 쓰는 쪽 (구현: search/infrastructure/OpenSearchProductIndex).
 *
 * <p>모든 쓰기에 상품 버전(PRODUCT.version)을 함께 넘긴다. 인덱스는 저장된 버전보다 큰 쓰기만 반영하므로,
 * 전체 색인과 이벤트 색인이 동시에 돌아 옛 값을 늦게 써도 새 값을 덮지 않는다.
 * 같거나 옛 버전이면 예외 없이 건너뛴다 (같은 이벤트를 두 번 받아도 결과가 같음).
 */
public interface ProductSearchIndex {

    void save(ProductDocument document, long version);

    void delete(Long productId, long version);

    // 전체 색인: 여러 건을 한 요청으로. 실패한 건이 있어도 나머지는 반영하고 결과로 알려 줌
    BulkResult bulk(List<Write> writes);

    // 한 건의 쓰기. document가 null이면 삭제
    record Write(Long productId, ProductDocument document, long version) {

        public static Write save(ProductDocument document, long version) {
            return new Write(document.productId(), document, version);
        }

        public static Write delete(Long productId, long version) {
            return new Write(productId, null, version);
        }

        public boolean isDelete() {
            return document == null;
        }
    }

    // applied: 반영, skipped: 같거나 더 새 버전이 이미 있음, failed: 그 밖의 오류
    record BulkResult(int applied, int skipped, int failed) {
    }
}

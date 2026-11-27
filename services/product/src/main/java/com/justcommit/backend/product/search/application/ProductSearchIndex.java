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

    // 전체 색인: 여러 건을 alias에 한 요청으로. 실패한 건이 있어도 나머지는 반영하고 결과로 알려 줌
    BulkResult bulk(List<Write> writes);

    // 무중단 재색인 (#487) ---------------------------------------------------------------

    // alias가 지금 가리키는 실제 인덱스 이름. alias가 없으면 IllegalStateException
    String currentIndex();

    // 지금 매핑으로 다음 번호의 인덱스를 alias 없이 만들고 이름을 돌려줌 (products_v1 → products_v2).
    // 실패한 재색인이 남긴 인덱스가 있으면 그보다 큰 번호 (이름을 다시 쓰지 않음, #563)
    String createNextIndex(String current);

    // alias가 아닌 특정 인덱스에 bulk (재색인 중 새 인덱스를 채울 때)
    BulkResult bulkInto(String index, List<Write> writes);

    // 쓴 문서가 바로 검색되게 (기본은 1초마다). alias를 옮기기 전에 새 인덱스를 다 보이게 하려고
    void refresh(String index);

    // alias를 from에서 to로 한 번에 옮김
    void switchAlias(String from, String to);

    void deleteIndex(String index);

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

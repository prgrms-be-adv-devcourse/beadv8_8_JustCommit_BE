package com.justcommit.backend.product.messaging;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 시나리오 자리표시: 상품 검색 색인(OpenSearch)이 아직 없음 (#485)
// 색인 리스너를 만드는 PR에서 본문을 채우고 @Disabled를 뗀다 (docs/testing/scenarios.md)
@Disabled("기능 없음 · OpenSearch 색인 #485")
class ProductSearchIndexScenarioTest {

    @Test
    @DisplayName("[S-C01] product.events.v1 → 색인 리스너 → 등록한 상품이 OpenSearch 검색에 나옴")
    void registeredProductIsSearchable() {
    }

    @Test
    @DisplayName("[S-C02] 숨긴 상품은 OpenSearch 검색에서 빠짐")
    void hiddenProductIsRemovedFromIndex() {
    }
}

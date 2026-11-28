package com.justcommit.backend.product.search.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 재색인 새 인덱스 이름 규칙 (#563)
class ProductIndexAdminTest {

    @Test
    @DisplayName("작업 ID가 있으면 가장 큰 번호 + 1 뒤에 _j{작업 ID}를 붙임")
    void appendsJobId() {
        assertThat(ProductIndexAdmin.nextIndexName("products_v1", List.of("products_v1"), 12L))
                .isEqualTo("products_v2_j12");
    }

    @Test
    @DisplayName("멈춘 재색인 인덱스를 지워 번호가 다시 비어도 작업마다 이름이 달라 같은 이름을 다시 쓰지 않음")
    void differentNamePerJobAfterCleanup() {
        String first = ProductIndexAdmin.nextIndexName("products_v1", List.of("products_v1"), 12L);
        // first를 정리해 지운 뒤 같은 상태에서 다음 작업
        String second = ProductIndexAdmin.nextIndexName("products_v1", List.of("products_v1"), 13L);

        assertThat(first).isEqualTo("products_v2_j12");
        assertThat(second).isEqualTo("products_v2_j13").isNotEqualTo(first);
    }

    @Test
    @DisplayName("작업 ID가 붙은 인덱스도 번호를 읽어 다음 번호를 정함")
    void readsNumberOfJobIndex() {
        assertThat(ProductIndexAdmin.nextIndexName("products_v3_j12", List.of("products_v3_j12", "products_v1"), 20L))
                .isEqualTo("products_v4_j20");
    }

    @Test
    @DisplayName("작업 ID가 없으면 지금처럼 products_v{n+1}")
    void withoutJobId() {
        assertThat(ProductIndexAdmin.nextIndexName("products_v1", List.of("products_v1", "products_v2"), null))
                .isEqualTo("products_v3");
    }
}

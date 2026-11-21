package com.justcommit.backend.product.species.presentation.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;

public class SpeciesSearchRequestTest {

    @Test
    @DisplayName("페이지 요청에는 정렬을 싣지 않음 (가나다순 · 「기타」 맨 뒤 정렬은 SpeciesSpecifications가 정하고, Sort가 있으면 덮어씀)")
    void noSortInPageable() {
        Pageable pageable = new SpeciesSearchRequest(null, null, null, null).toPageable();

        assertThat(pageable.getSort().isUnsorted()).isTrue();
    }

    @Test
    @DisplayName("size를 보내지 않으면 품종을 500개까지 조회")
    void defaultSizeIs500() {
        Pageable pageable = new SpeciesSearchRequest(null, null, null, null).toPageable();

        assertThat(pageable.getPageSize()).isEqualTo(500);
    }

    @Test
    @DisplayName("size가 500을 넘으면 500으로 제한")
    void sizeIsLimitedTo500() {
        Pageable pageable = new SpeciesSearchRequest(null, null, null, 1000).toPageable();

        assertThat(pageable.getPageSize()).isEqualTo(500);
    }
}

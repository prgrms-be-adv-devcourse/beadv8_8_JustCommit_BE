package com.justcommit.backend.product.species.presentation.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

public class SpeciesSearchRequestTest {

    // 추후에 정렬 및 필터링 시 추가할 예정
    @Test
    @DisplayName("품종 목록은 이름 오름차순으로 정렬")
    void sortByNameAsc() {
        Pageable pageable = new SpeciesSearchRequest(null, null, null, null).toPageable();


        assertThat(pageable.getSort().getOrderFor("name").getDirection()).isEqualTo(Sort.Direction.ASC);
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

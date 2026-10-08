package com.justcommit.backend.product.presentation.dto;

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
}

package com.justcommit.backend.common.request;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import static org.assertj.core.api.Assertions.assertThat;

public class PagingRequestTest {

    record DefaultRequest(Integer page, Integer size) implements  PagingRequest{

    }

    record CustomRequest(Integer page, Integer size) implements PagingRequest{
        @Override
        public int defaultSize() {
            return 10;
        }

        @Override
        public int maxSize() {
            return 100;
        }

        @Override
        public Sort defaultSort() {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
    }

    @Test
    @DisplayName("page, size를 보내지 않으면 기본값(0, 20)을 사용하고 정렬하지 않음")
    void defaultPaging() {
        Pageable pageable = new DefaultRequest(null, null).toPageable();

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getSort().isUnsorted()).isTrue();
    }

    @Test
    @DisplayName("size가 최대값(50)을 넘으면 50으로 보정")
    void sizeOverMaxIsLimited() {
        Pageable pageable = new DefaultRequest(0, 100).toPageable();

        assertThat(pageable.getPageSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("page가 음수이거나 size가 1보다 작으면 기본값으로 보정")
    void invalidPagingUsesDefault() {
        Pageable pageable = new DefaultRequest(-1, 0).toPageable();

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("기본 크기, 최대 크기, 정렬을 오버라이드하면 그 값을 사용")
    void overriddenPaging() {
        Pageable noSize = new CustomRequest(null, null).toPageable();
        Pageable overMax = new CustomRequest(0, 500).toPageable();

        assertThat(noSize.getPageSize()).isEqualTo(10);
        assertThat(overMax.getPageSize()).isEqualTo(100);
        assertThat(noSize.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
    }
}

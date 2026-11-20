package com.justcommit.backend.product.review.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewEngineExceptionTest {

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 413, 422})
    @DisplayName("요청 자체가 거부된 4xx는 재처리하지 않는 오류")
    void clientErrors(int status) {
        assertThat(ReviewEngineException.isClientError(status)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 503, 200})
    @DisplayName("408 · 429 · 5xx는 잠깐 뒤 다시 하면 될 수 있어 재처리 대상 (#597 리뷰)")
    void retryableStatuses(int status) {
        assertThat(ReviewEngineException.isClientError(status)).isFalse();
    }
}

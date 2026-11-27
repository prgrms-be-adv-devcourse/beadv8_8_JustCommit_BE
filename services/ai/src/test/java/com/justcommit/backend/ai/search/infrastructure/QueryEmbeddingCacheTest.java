package com.justcommit.backend.ai.search.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 질문 임베딩 캐시 (#636): 키 · 적중 지표 · 실패는 저장하지 않음
class QueryEmbeddingCacheTest {

    private static final String MODEL = "text-embedding-3-small";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final QueryEmbeddingCache cache = new QueryEmbeddingCache(100, Duration.ofHours(24), registry);
    private final AtomicInteger loads = new AtomicInteger();

    private float[] load() {
        loads.incrementAndGet();
        return new float[] {1f, 2f};
    }

    @Test
    @DisplayName("앞뒤 공백을 지우고 줄바꿈 · 탭을 포함한 연속 공백을 하나로 줄인다")
    void normalizesSpaces() {
        assertThat(QueryEmbeddingCache.normalize("  햇빛   적은\n\t방 ")).isEqualTo("햇빛 적은 방");
    }

    @Test
    @DisplayName("같은 모델 · 같은 문장은 두 번째부터 저장한 벡터를 돌려주고 적중 · 미적중을 센다")
    void hitsOnSameQuery() {
        float[] first = cache.get(MODEL, 1536, "햇빛 적은 방", this::load);
        float[] second = cache.get(MODEL, 1536, "햇빛 적은 방", this::load);

        assertThat(second).containsExactly(first);
        assertThat(loads).hasValue(1);
        assertThat(count("hit")).isEqualTo(1);
        assertThat(count("miss")).isEqualTo(1);
        assertThat(registry.get(QueryEmbeddingCache.METRIC + ".size").gauge().value()).isEqualTo(1);
    }

    @Test
    @DisplayName("돌려준 벡터를 바꿔도 저장한 벡터는 그대로다")
    void returnsCopies() {
        cache.get(MODEL, 1536, "햇빛 적은 방", this::load)[0] = 99f;

        assertThat(cache.get(MODEL, 1536, "햇빛 적은 방", this::load)).containsExactly(1f, 2f);
    }

    @Test
    @DisplayName("문장 · 모델 · 차원 중 하나라도 다르면 다른 키다")
    void differentKeys() {
        cache.get(MODEL, 1536, "햇빛 적은 방", this::load);
        cache.get(MODEL, 1536, "햇빛 많은 방", this::load);
        cache.get("text-embedding-3-large", 1536, "햇빛 적은 방", this::load);
        cache.get(MODEL, 512, "햇빛 적은 방", this::load);

        assertThat(loads).hasValue(4);
        assertThat(count("hit")).isZero();
    }

    @Test
    @DisplayName("임베딩이 실패하면 저장하지 않아 다음 요청에서 다시 부른다")
    void doesNotCacheFailure() {
        assertThatThrownBy(() -> cache.get(MODEL, 1536, "햇빛 적은 방", () -> {
            throw new IllegalStateException("시간 초과");
        })).isInstanceOf(IllegalStateException.class);

        cache.get(MODEL, 1536, "햇빛 적은 방", this::load);
        assertThat(loads).hasValue(1);
    }

    private double count(String result) {
        return registry.get(QueryEmbeddingCache.METRIC).tag("result", result).counter().count();
    }
}

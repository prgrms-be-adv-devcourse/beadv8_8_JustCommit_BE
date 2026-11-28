package com.justcommit.backend.ai.search.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 질문 임베딩 캐시 (#636): 키(범위 포함) · 적중 지표 · 실패는 저장하지 않음
class QueryEmbeddingCacheTest {

    private static final String MODEL = "text-embedding-3-small";
    private static final String SCOPE = "species-search";

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
        float[] first = cache.get(SCOPE, MODEL, 1536, "햇빛 적은 방", this::load);
        float[] second = cache.get(SCOPE, MODEL, 1536, "햇빛 적은 방", this::load);

        assertThat(second).containsExactly(first);
        assertThat(loads).hasValue(1);
        assertThat(count("hit")).isEqualTo(1);
        assertThat(count("miss")).isEqualTo(1);
        assertThat(registry.get(QueryEmbeddingCache.METRIC + ".size").gauge().value()).isEqualTo(1);
    }

    @Test
    @DisplayName("돌려준 벡터를 바꿔도 저장한 벡터는 그대로다")
    void returnsCopies() {
        cache.get(SCOPE, MODEL, 1536, "햇빛 적은 방", this::load)[0] = 99f;

        assertThat(cache.get(SCOPE, MODEL, 1536, "햇빛 적은 방", this::load)).containsExactly(1f, 2f);
    }

    @Test
    @DisplayName("문장 · 모델 · 차원 중 하나라도 다르면 다른 키다")
    void differentKeys() {
        cache.get(SCOPE, MODEL, 1536, "햇빛 적은 방", this::load);
        cache.get(SCOPE, MODEL, 1536, "햇빛 많은 방", this::load);
        cache.get(SCOPE, "text-embedding-3-large", 1536, "햇빛 적은 방", this::load);
        cache.get(SCOPE, MODEL, 512, "햇빛 적은 방", this::load);

        assertThat(loads).hasValue(4);
        assertThat(count("hit")).isZero();
    }

    @Test
    @DisplayName("범위가 다르면 같은 문장이어도 다른 키다 (공개 품종 검색 · 회원별 챗봇, #636 리뷰)")
    void differentScopes() {
        cache.get("species-search", MODEL, 1536, "햇빛 적은 방", this::load);
        cache.get("chatbot:1", MODEL, 1536, "햇빛 적은 방", this::load);
        cache.get("chatbot:2", MODEL, 1536, "햇빛 적은 방", this::load);
        cache.get("chatbot:1", MODEL, 1536, "햇빛 적은 방", this::load);

        assertThat(loads).hasValue(3);
        assertThat(count("hit")).isEqualTo(1);
    }

    @Test
    @DisplayName("임베딩이 실패하면 저장하지 않아 다음 요청에서 다시 부른다")
    void doesNotCacheFailure() {
        assertThatThrownBy(() -> cache.get(SCOPE, MODEL, 1536, "햇빛 적은 방", () -> {
            throw new IllegalStateException("시간 초과");
        })).isInstanceOf(IllegalStateException.class);

        cache.get(SCOPE, MODEL, 1536, "햇빛 적은 방", this::load);
        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("같은 문장이 동시에 들어오면 OpenAI는 한 번만 부르고, 기다린 요청도 같은 벡터를 받는다")
    void loadsOnceForConcurrentSameQuery() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<float[]> first = CompletableFuture.supplyAsync(() -> cache.get(SCOPE, MODEL, 1536, "햇빛 적은 방", () -> {
            loading.countDown();
            await(release);
            return load();
        }));
        assertThat(loading.await(2, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<float[]> second = CompletableFuture.supplyAsync(() -> cache.get(SCOPE, MODEL, 1536, "햇빛 적은 방", this::load));
        Thread.sleep(100);
        assertThat(second).isNotDone();   // 먼저 온 요청의 결과를 기다림
        release.countDown();

        assertThat(first.get(2, TimeUnit.SECONDS)).containsExactly(1f, 2f);
        assertThat(second.get(2, TimeUnit.SECONDS)).containsExactly(1f, 2f);
        assertThat(loads).hasValue(1);
        assertThat(count("miss")).isEqualTo(1);
        assertThat(count("hit")).isEqualTo(1);
    }

    @Test
    @DisplayName("먼저 온 요청의 임베딩이 실패하면 기다리던 요청도 같은 예외를 받고, 그다음 요청은 다시 부른다")
    void waitersGetSameFailure() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        IllegalStateException timeout = new IllegalStateException("시간 초과");
        CompletableFuture<float[]> first = CompletableFuture.supplyAsync(() -> cache.get(SCOPE, MODEL, 1536, "햇빛 적은 방", () -> {
            loading.countDown();
            await(release);
            throw timeout;
        }));
        assertThat(loading.await(2, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<float[]> second = CompletableFuture.supplyAsync(() -> cache.get(SCOPE, MODEL, 1536, "햇빛 적은 방", this::load));
        Thread.sleep(100);
        release.countDown();

        assertThatThrownBy(() -> first.get(2, TimeUnit.SECONDS)).hasCause(timeout);
        assertThatThrownBy(() -> second.get(2, TimeUnit.SECONDS)).hasCause(timeout);
        assertThat(cache.get(SCOPE, MODEL, 1536, "햇빛 적은 방", this::load)).containsExactly(1f, 2f);
        assertThat(loads).hasValue(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private double count(String result) {
        return registry.get(QueryEmbeddingCache.METRIC).tag("result", result).counter().count();
    }
}

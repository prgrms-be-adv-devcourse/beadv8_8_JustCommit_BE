package com.justcommit.backend.ai.search.infrastructure;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 질문 임베딩 캐시 (#636). 품종 의미 검색 · 챗봇이 같은 질문을 다시 보내면 OpenAI를 부르지 않고 저장한 벡터를 쓴다.
 * 키: 모델 · 차원 · 정리한 질문(앞뒤 공백 제거, 연속 공백 하나로)의 SHA-256. 챗봇 이어 묻기는 「직전 질문 + 이번 질문」 문장이 그대로 질문이다.
 *
 * <p>저장소는 ai 파드 메모리(Caffeine)다. ai는 Redis를 쓰지 않고, 공용 Redis는 256MB · noeviction이라
 * 벡터(1536차원 ≈ 6KB)가 쌓이면 로그인 토큰 쓰기까지 실패할 수 있어서다 (docs/reference/redis-keys.md 「설정」).
 * 크기 상한(기본 1,000개 ≈ 7MB)을 넘으면 오래 안 쓴 것부터 지우고, TTL(기본 24시간)이 지나면 다시 임베딩한다.
 * 파드마다 따로 갖고 재시작하면 비지만, 캐시가 없어도 원래대로 OpenAI를 부를 뿐이다.
 *
 * <p>같은 질문이 동시에 들어오면 먼저 온 요청만 OpenAI를 부르고, 나머지는 그 결과를 기다려 같은 벡터를 쓴다.
 * 진행 중인 계산을 future로 저장해 두는 방식이라, 기다리는 동안 다른 질문의 캐시 조회는 막지 않는다.
 *
 * <p>지표: {@code ai.query_embedding.cache}(result=hit · miss) 카운터, {@code ai.query_embedding.cache.size} 게이지.
 * miss는 OpenAI를 직접 부른 요청, hit은 저장한 벡터나 진행 중인 계산을 쓴 요청이다.
 * 실패한 임베딩은 저장하지 않는다 (기다리던 요청도 같은 예외를 받고, 다음 요청은 다시 부른다).
 */
@Component
public class QueryEmbeddingCache {

    public static final String METRIC = "ai.query_embedding.cache";
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final AsyncCache<String, float[]> cache;
    private final Counter hits;
    private final Counter misses;

    public QueryEmbeddingCache(
            @Value("${custom.query-embedding-cache.max-size:1000}") long maxSize,
            @Value("${custom.query-embedding-cache.ttl:24h}") Duration ttl,
            MeterRegistry meterRegistry) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttl)
                .buildAsync();
        this.hits = Counter.builder(METRIC).description("질문 임베딩 캐시 (#636)").tag("result", "hit")
                .register(meterRegistry);
        this.misses = Counter.builder(METRIC).description("질문 임베딩 캐시 (#636)").tag("result", "miss")
                .register(meterRegistry);
        Gauge.builder(METRIC + ".size", cache, c -> c.synchronous().estimatedSize()).description("질문 임베딩 캐시에 든 벡터 수 (#636)")
                .register(meterRegistry);
    }

    /** 검색 문장 정리: 앞뒤 공백 제거, 줄바꿈 · 탭을 포함한 연속 공백은 공백 하나로 */
    public static String normalize(String query) {
        return SPACES.matcher(query.strip()).replaceAll(" ");
    }

    /**
     * 정리한 문장(normalized)의 벡터. 없으면 loader로 만들어 저장한다. loader 예외는 그대로 던지고 저장하지 않는다.
     * 같은 질문이 동시에 들어오면 loader는 한 번만 부르고, 나머지는 그 결과를 기다린다
     */
    public float[] get(String model, int dimensions, String normalized, Supplier<float[]> loader) {
        String key = key(model, dimensions, normalized);
        CompletableFuture<float[]> mine = new CompletableFuture<>();
        CompletableFuture<float[]> existing = cache.asMap().putIfAbsent(key, mine);
        if (existing != null) {
            hits.increment();
            return join(existing).clone();
        }
        misses.increment();
        try {
            float[] vector = loader.get();
            mine.complete(vector.clone());
            return vector;
        } catch (RuntimeException | Error e) {
            // 실패한 future는 Caffeine이 지우지만, 다음 요청이 바로 다시 부를 수 있게 먼저 지운다
            cache.asMap().remove(key, mine);
            mine.completeExceptionally(e);
            throw e;
        }
    }

    // 다른 요청이 하던 계산의 결과. 실패면 그 요청이 받은 예외를 감싸지 않고 그대로 던진다
    private static float[] join(CompletableFuture<float[]> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    static String key(String model, int dimensions, String normalized) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest((model + "\n" + dimensions + "\n" + normalized).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 없음", e);
        }
    }
}

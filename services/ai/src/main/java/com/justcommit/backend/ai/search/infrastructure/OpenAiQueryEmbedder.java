package com.justcommit.backend.ai.search.infrastructure;

import com.justcommit.backend.ai.search.application.QueryEmbedder;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 품종 의미 검색용 OpenAI 임베딩 (docs/conventions/resilience.md 「ai → OpenAI 임베딩 (품종 의미 검색)」).
 * 사용자가 기다리는 요청이라 재임베딩용 자동 설정 모델(재시도 3회 · 긴 타임아웃)을 쓰지 않고,
 * 같은 키 · 모델 · 차원으로 타임아웃 3초 · 재시도 없음인 모델을 따로 만든다.
 * EmbeddingModel 빈으로 등록하지 않아 자동 설정 모델(재임베딩 · 벡터 저장소)에는 영향이 없다.
 * 문장은 정리(앞뒤 · 연속 공백)해서 보내고, 같은 범위(cacheScope)의 같은 문장은 {@link QueryEmbeddingCache}에서 꺼내 OpenAI를 다시 부르지 않는다 (#636).
 */
@Slf4j
@Component
public class OpenAiQueryEmbedder implements QueryEmbedder {

    private final OpenAiEmbeddingModel embeddingModel;
    private final QueryEmbeddingCache cache;
    private final String model;
    private final int dimensions;

    public OpenAiQueryEmbedder(
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.base-url:}") String baseUrl,
            @Value("${custom.embedding.model}") String model,
            @Value("${custom.embedding.dimensions}") int dimensions,
            @Value("${custom.species-search.timeout:3s}") Duration timeout,
            QueryEmbeddingCache cache) {
        this.cache = cache;
        this.model = model;
        this.dimensions = dimensions;
        OpenAiEmbeddingOptions.Builder options = OpenAiEmbeddingOptions.builder();
        options.model(model);
        options.dimensions(dimensions);
        options.timeout(timeout);
        options.maxRetries(0);
        if (!baseUrl.isBlank()) {
            options.baseUrl(baseUrl);
        }
        // 키가 없으면 OpenAI 클라이언트를 만들 때 예외가 나 앱 시작이 멈춘다. 검색만 503이 되게 모델을 만들지 않는다
        if (apiKey.isBlank()) {
            log.warn("OpenAI 키가 없어 품종 의미 검색은 503으로 응답합니다");
            this.embeddingModel = null;
            return;
        }
        options.apiKey(apiKey);
        this.embeddingModel = OpenAiEmbeddingModel.builder().options(options.build()).build();
    }

    @Override
    public float[] embed(String query, String cacheScope) {
        if (embeddingModel == null) {
            throw new QueryEmbeddingException("OpenAI 키 없음", null);
        }
        String normalized = QueryEmbeddingCache.normalize(query);
        return cache.get(cacheScope, model, dimensions, normalized, () -> callOpenAi(normalized));
    }

    private float[] callOpenAi(String query) {
        try {
            return embeddingModel.embed(query);
        } catch (RuntimeException e) {
            throw new QueryEmbeddingException("검색 문장 임베딩 실패: " + e.getClass().getSimpleName(), e);
        }
    }
}

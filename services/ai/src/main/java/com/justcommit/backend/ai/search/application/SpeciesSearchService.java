package com.justcommit.backend.ai.search.application;

import com.justcommit.backend.ai.common.AiErrorCode;
import com.justcommit.backend.ai.common.StageTimers;
import com.justcommit.backend.ai.search.application.QueryEmbedder.QueryEmbeddingException;
import com.justcommit.backend.ai.search.infrastructure.SpeciesSearchQueryRepository;
import com.justcommit.backend.ai.search.infrastructure.SpeciesSearchQueryRepository.ActiveProduct;
import com.justcommit.backend.ai.search.infrastructure.SpeciesSearchQueryRepository.SimilarSpecies;
import com.justcommit.backend.common.exception.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 품종 의미 검색 (docs/flows/search-recommend.md 「품종 의미 검색」, ADR 0030).
 * 문장 → 질의 벡터(OpenAI) → species_vector_store에서 가까운 품종 → 상품 스냅샷에서 그 품종의 판매 중 상품
 *
 * <p>단계별 시간(#634)은 {@code ai.species_search.stage}: 질문 임베딩 · 벡터 검색 · 상품 조회 · 전체를 {@code outcome}
 * (success · failure)으로 나눠 남긴다. 입력 검증(400)에 걸린 요청은 남기지 않는다. 구간 범위 5ms ~ 5초 (임베딩 시간 초과 3초).
 */
@Slf4j
@Service
public class SpeciesSearchService {

    public static final int MIN_QUERY_LENGTH = 2;
    public static final int MAX_QUERY_LENGTH = 100;
    public static final int MAX_SIZE = 10;
    public static final int PRODUCTS_PER_SPECIES = 3;

    public static final String METRIC = "ai.species_search.stage";
    public static final String STAGE_EMBEDDING = "embedding";
    public static final String STAGE_VECTOR_SEARCH = "vector_search";
    public static final String STAGE_PRODUCT_LOOKUP = "product_lookup";
    public static final String STAGE_TOTAL = "total";

    private final QueryEmbedder queryEmbedder;
    private final SpeciesSearchQueryRepository queryRepository;
    private final double minSimilarity;
    private final StageTimers timers;

    public SpeciesSearchService(
            QueryEmbedder queryEmbedder,
            SpeciesSearchQueryRepository queryRepository,
            @Value("${custom.species-search.min-similarity:0.35}") double minSimilarity,
            MeterRegistry meterRegistry) {
        this.queryEmbedder = queryEmbedder;
        this.queryRepository = queryRepository;
        this.minSimilarity = minSimilarity;
        this.timers = new StageTimers(meterRegistry, METRIC, "품종 의미 검색 단계별 시간 (#634)",
                List.of(STAGE_EMBEDDING, STAGE_VECTOR_SEARCH, STAGE_PRODUCT_LOOKUP, STAGE_TOTAL),
                Duration.ofMillis(5), Duration.ofSeconds(5));
    }

    public List<SpeciesSearchResult> search(String rawQuery, int size) {
        String query = rawQuery == null ? "" : rawQuery.strip();
        if (query.length() < MIN_QUERY_LENGTH || query.length() > MAX_QUERY_LENGTH) {
            throw new BusinessException(AiErrorCode.INVALID_SEARCH_QUERY);
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new BusinessException(AiErrorCode.INVALID_SEARCH_SIZE);
        }

        return timers.time(STAGE_TOTAL, () -> searchValid(query, size));
    }

    private List<SpeciesSearchResult> searchValid(String query, int size) {
        long start = System.nanoTime();
        float[] vector;
        try {
            vector = timers.time(STAGE_EMBEDDING, () -> queryEmbedder.embed(query, QueryEmbedder.SPECIES_SEARCH_SCOPE));
        } catch (QueryEmbeddingException e) {
            // 검색만 실패시킨다. 문장은 로그에 남기지 않음 (사용자 입력)
            log.warn("품종 의미 검색 임베딩 실패: {}", e.getMessage());
            throw new BusinessException(AiErrorCode.SEMANTIC_SEARCH_UNAVAILABLE);
        }

        long vectorAt = System.nanoTime();
        List<SimilarSpecies> species = timers.time(STAGE_VECTOR_SEARCH,
                () -> queryRepository.findSimilarSpecies(vector, minSimilarity, size));

        Map<Integer, List<Long>> products = new HashMap<>();
        for (ActiveProduct p : timers.time(STAGE_PRODUCT_LOOKUP, () -> queryRepository.findActiveProducts(
                species.stream().map(SimilarSpecies::speciesId).toList(), PRODUCTS_PER_SPECIES))) {
            products.computeIfAbsent(p.speciesId(), id -> new ArrayList<>()).add(p.productId());
        }

        List<SpeciesSearchResult> result = species.stream()
                .map(s -> new SpeciesSearchResult(s.speciesId(), s.name(), round(s.similarity()),
                        products.getOrDefault(s.speciesId(), List.of())))
                .toList();
        log.debug("품종 의미 검색: 품종 {}개, 임베딩 {}ms, 조회 {}ms", result.size(),
                (vectorAt - start) / 1_000_000, (System.nanoTime() - vectorAt) / 1_000_000);
        return result;
    }

    private static double round(double similarity) {
        return Math.round(similarity * 1000) / 1000.0;
    }
}

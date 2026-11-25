package com.justcommit.backend.product.search.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 키워드 상품 검색의 단계별 시간 (#634). 지표 하나에 단계를 {@code stage} 태그로 나눈다:
 * Prometheus {@code product_search_stage_seconds_bucket{stage="…"}}, Grafana에서 단계별 p50 · p95를 같은 그래프로.
 *
 * <p>구간 히스토그램을 내보내 여러 파드 값을 합친 p95를 Prometheus에서 계산한다 (응답 시간 {@code http.server.requests}와 같은 방식).
 * 단계별 Timer는 시작할 때 미리 만들어 둔다: 장애 대체처럼 드문 단계도 0건으로 보여 「없음」과 「아직 안 남김」이 구분되게.
 */
@Component
public class ProductSearchMetrics {

    public static final String NAME = "product.search.stage";

    public enum Stage {
        /** 키워드 검색 전체 (OpenSearch → DB 재조회, 또는 실패 뒤 LIKE 대체까지) */
        TOTAL("total"),
        /** product가 본 OpenSearch 호출 시간 (동시 검색 자리 대기 · 연결 실패 재시도 · 네트워크 포함). 성공만 */
        OPENSEARCH("opensearch"),
        /** OpenSearch 응답의 took: OpenSearch 안에서 검색에 쓴 시간. opensearch와의 차이가 네트워크 · 대기 · 직렬화 */
        OPENSEARCH_TOOK("opensearch_took"),
        /** OpenSearch가 준 id로 DB에서 화면 값을 다시 읽는 시간 */
        DB("db"),
        /** OpenSearch 실패 뒤 DB LIKE 검색 시간 (실패까지 기다린 시간은 빼고, total에는 들어감) */
        FALLBACK("fallback");

        private final String tag;

        Stage(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    private final Map<Stage, Timer> timers = new EnumMap<>(Stage.class);

    public ProductSearchMetrics(MeterRegistry registry) {
        for (Stage stage : Stage.values()) {
            timers.put(stage, Timer.builder(NAME)
                    .description("키워드 상품 검색 단계별 시간 (#634)")
                    .tag("stage", stage.tag())
                    .publishPercentileHistogram()
                    .maximumExpectedValue(Duration.ofSeconds(10))
                    .register(registry));
        }
    }

    public void record(Stage stage, long startedNanos) {
        timers.get(stage).record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }

    public void recordMillis(Stage stage, long millis) {
        timers.get(stage).record(millis, TimeUnit.MILLISECONDS);
    }
}

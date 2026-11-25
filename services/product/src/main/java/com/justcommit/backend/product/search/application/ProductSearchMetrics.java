package com.justcommit.backend.product.search.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 키워드 상품 검색의 단계별 시간 (#634). 지표 하나에 단계를 {@code stage} 태그로, 성공 · 실패를 {@code outcome} 태그로 나눈다:
 * Prometheus {@code product_search_stage_seconds_bucket{stage="…",outcome="success|failure"}}.
 * 실패도 남겨야 OpenSearch 시간 초과로 LIKE 대체까지 기다린 시간이 어느 단계에서 쓰였는지 보인다 (#649 리뷰).
 *
 * <p>구간 히스토그램을 내보내 여러 파드 값을 합친 p95를 Prometheus에서 계산한다 ({@code http.server.requests}와 같은 방식).
 * 구간 범위는 1ms ~ 5초 (OpenSearch 읽기 시간 초과 2초 + LIKE 대체를 덮는 정도)로 좁혀 시계열 수를 줄인다.
 * 성공 Timer는 시작할 때 미리 만들어 「0건」과 「아직 안 남김」을 구분하고, 실패 Timer는 처음 실패할 때 만든다
 * (0건 히스토그램도 구간마다 시계열이 생기므로, 드문 실패까지 미리 만들지 않음).
 */
@Component
public class ProductSearchMetrics {

    public static final String NAME = "product.search.stage";
    public static final String SUCCESS = "success";
    public static final String FAILURE = "failure";
    static final Duration MIN = Duration.ofMillis(1);
    static final Duration MAX = Duration.ofSeconds(5);

    public enum Stage {
        /** 키워드 검색 전체 (OpenSearch → DB 재조회, 또는 실패 뒤 LIKE 대체까지). 대체로 응답했으면 성공 */
        TOTAL("total"),
        /** product가 본 OpenSearch 호출 시간 (동시 검색 자리 대기 · 연결 실패 재시도 · 네트워크 포함). 실패는 포기할 때까지 기다린 시간 */
        OPENSEARCH("opensearch"),
        /** OpenSearch 응답의 took: OpenSearch 안에서 검색에 쓴 시간. opensearch와의 차이가 네트워크 · 대기 · 직렬화. 성공만 */
        OPENSEARCH_TOOK("opensearch_took"),
        /** OpenSearch가 준 id로 DB에서 화면 값을 다시 읽는 시간 */
        DB("db"),
        /** OpenSearch 실패 뒤 DB LIKE 검색 시간 (실패까지 기다린 시간은 opensearch 실패에, 둘 다 total에 들어감) */
        FALLBACK("fallback");

        private final String tag;

        Stage(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    private final MeterRegistry registry;
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    public ProductSearchMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (Stage stage : Stage.values()) {
            timer(stage, SUCCESS);
        }
    }

    public void record(Stage stage, long startedNanos) {
        timer(stage, SUCCESS).record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }

    public void recordFailure(Stage stage, long startedNanos) {
        timer(stage, FAILURE).record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }

    public void recordMillis(Stage stage, long millis) {
        timer(stage, SUCCESS).record(millis, TimeUnit.MILLISECONDS);
    }

    private Timer timer(Stage stage, String outcome) {
        return timers.computeIfAbsent(stage.tag() + "/" + outcome, key -> Timer.builder(NAME)
                .description("키워드 상품 검색 단계별 시간 (#634)")
                .tag("stage", stage.tag())
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .minimumExpectedValue(MIN)
                .maximumExpectedValue(MAX)
                .register(registry));
    }
}

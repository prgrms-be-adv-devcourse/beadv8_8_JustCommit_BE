package com.justcommit.backend.ai.common;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 한 요청의 단계별 시간 (#634). 지표 하나에 단계를 {@code stage} 태그로, 성공 · 실패를 {@code outcome} 태그로 나눈다
 * (예: {@code ai_chatbot_stage_seconds_bucket{stage="embedding",outcome="failure"}}).
 * 실패도 남겨야 시간 초과로 실패한 요청이 어느 단계에서 기다렸는지 보인다 (#649 리뷰).
 *
 * <p>구간 히스토그램을 내보내 Prometheus에서 p95를 계산한다 ({@code http.server.requests}와 같은 방식).
 * 구간 범위(min ~ max)는 지표마다 정해 시계열 수를 줄인다.
 * 성공 Timer는 시작할 때 미리 만들어 「0건」과 「아직 안 남김」을 구분하고, 실패 Timer는 처음 실패할 때 만든다
 * (0건 히스토그램도 구간마다 시계열이 생기므로, 드문 실패까지 미리 만들지 않음).
 *
 * <p>product의 {@code ProductSearchMetrics}와 모양이 비슷하지만 서비스끼리 코드를 나누지 않으려고 따로 둔다.
 */
public final class StageTimers {

    public static final String SUCCESS = "success";
    public static final String FAILURE = "failure";

    private final MeterRegistry registry;
    private final String name;
    private final String description;
    private final Set<String> stages;
    private final Duration min;
    private final Duration max;
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    public StageTimers(MeterRegistry registry, String name, String description, List<String> stages,
                       Duration min, Duration max) {
        this.registry = registry;
        this.name = name;
        this.description = description;
        this.stages = Set.copyOf(stages);
        this.min = min;
        this.max = max;
        stages.forEach(stage -> timer(stage, SUCCESS));
    }

    /** startedNanos({@link System#nanoTime()})부터 지금까지를 성공으로 남긴다 */
    public void record(String stage, long startedNanos) {
        timer(stage, SUCCESS).record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }

    /** startedNanos부터 지금까지를 실패로 남긴다 */
    public void recordFailure(String stage, long startedNanos) {
        timer(stage, FAILURE).record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }

    /** 단계를 실행하며 시간을 잰다. 예외가 나면 실패로 남기고 그대로 던진다 */
    public <T> T time(String stage, Supplier<T> step) {
        long startedAt = System.nanoTime();
        try {
            T result = step.get();
            record(stage, startedAt);
            return result;
        } catch (RuntimeException e) {
            recordFailure(stage, startedAt);
            throw e;
        }
    }

    private Timer timer(String stage, String outcome) {
        if (!stages.contains(stage)) {
            throw new IllegalArgumentException(name + "에 없는 단계: " + stage);
        }
        return timers.computeIfAbsent(stage + "/" + outcome, key -> Timer.builder(name)
                .description(description)
                .tag("stage", stage)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .minimumExpectedValue(min)
                .maximumExpectedValue(max)
                .register(registry));
    }
}

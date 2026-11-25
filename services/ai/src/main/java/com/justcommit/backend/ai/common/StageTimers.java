package com.justcommit.backend.ai.common;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 한 요청의 단계별 시간 (#634). 지표 하나에 단계를 {@code stage} 태그로 나눈다
 * (예: {@code ai_chatbot_stage_seconds_bucket{stage="embedding"}}). Grafana에서 단계별 p50 · p95를 같은 그래프로 본다.
 *
 * <p>구간 히스토그램을 내보내 Prometheus에서 p95를 계산한다 ({@code http.server.requests}와 같은 방식).
 * 단계별 Timer는 시작할 때 미리 만들어 둔다: 아직 안 지나간 단계도 0건으로 보이게.
 */
public final class StageTimers {

    private final String name;
    private final Map<String, Timer> timers = new HashMap<>();

    public StageTimers(MeterRegistry registry, String name, String description, List<String> stages) {
        this.name = name;
        for (String stage : stages) {
            timers.put(stage, Timer.builder(name)
                    .description(description)
                    .tag("stage", stage)
                    .publishPercentileHistogram()
                    .maximumExpectedValue(Duration.ofSeconds(30))
                    .register(registry));
        }
    }

    /** startedNanos({@link System#nanoTime()})부터 지금까지를 남긴다 */
    public void record(String stage, long startedNanos) {
        Timer timer = timers.get(stage);
        if (timer == null) {
            throw new IllegalArgumentException(name + "에 없는 단계: " + stage);
        }
        timer.record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }
}

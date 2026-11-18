package com.justcommit.backend.ai.recommendation.application;

import com.justcommit.backend.ai.species.domain.SpeciesProfile;

/**
 * 두 품종의 생육 환경 비교 (품종 프로필 값만 씀). 값이 없으면 중립 0.5, 겹치는지는 "모름"(null)
 *
 * @param score        0~1. 생육 온도 0.6 + 광도 0.4
 * @param tempOverlap  생육 온도 범위가 겹치면 true (모르면 null)
 * @param lightOverlap 광도 범위가 겹치면 true (모르면 null)
 */
public record EnvironmentMatch(double score, Boolean tempOverlap, Boolean lightOverlap) {

    static final double TEMP_WEIGHT = 0.6;
    static final double LIGHT_WEIGHT = 0.4;
    static final double UNKNOWN = 0.5;
    // 생육 온도 범위가 떨어져 있으면 1℃마다 0.1 감점 (16~20℃ vs 21~25℃ → 0.9). 평가 세트로 조정할 출발값
    static final double TEMP_PENALTY_PER_DEGREE = 0.1;
    // 광도 단계(LOW · MEDIUM · HIGH)가 한 단계 떨어질 때마다 0.5 감점
    static final double LIGHT_PENALTY_PER_LEVEL = 0.5;

    public static EnvironmentMatch of(SpeciesProfile candidate, SpeciesProfile base) {
        Integer tempGap = rangeGap(candidate.growthTempMin(), candidate.growthTempMax(),
                base.growthTempMin(), base.growthTempMax());
        Integer lightGap = rangeGap(lightLevel(candidate.lightMin()), lightLevel(candidate.lightMax()),
                lightLevel(base.lightMin()), lightLevel(base.lightMax()));

        double tempScore = tempGap == null ? UNKNOWN : Math.max(0, 1 - TEMP_PENALTY_PER_DEGREE * tempGap);
        double lightScore = lightGap == null ? UNKNOWN : Math.max(0, 1 - LIGHT_PENALTY_PER_LEVEL * lightGap);
        return new EnvironmentMatch(TEMP_WEIGHT * tempScore + LIGHT_WEIGHT * lightScore,
                tempGap == null ? null : tempGap == 0,
                lightGap == null ? null : lightGap == 0);
    }

    // 두 범위 사이 간격. 겹치면 0, 값이 하나라도 없으면 null
    static Integer rangeGap(Integer aMin, Integer aMax, Integer bMin, Integer bMax) {
        if (aMin == null || aMax == null || bMin == null || bMax == null) {
            return null;
        }
        return Math.max(0, Math.max(aMin, bMin) - Math.min(aMax, bMax));
    }

    static Integer lightLevel(String light) {
        if (light == null) {
            return null;
        }
        return switch (light) {
            case "LOW" -> 0;
            case "MEDIUM" -> 1;
            case "HIGH" -> 2;
            default -> null;
        };
    }
}

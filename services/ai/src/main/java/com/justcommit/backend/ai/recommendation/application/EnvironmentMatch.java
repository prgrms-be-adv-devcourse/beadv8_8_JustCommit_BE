package com.justcommit.backend.ai.recommendation.application;

import com.justcommit.backend.ai.species.domain.SpeciesProfile;

/**
 * 두 품종의 생육 환경 비교 (품종 프로필 값만 씀). 값이 없으면 중립 0.5, 겹치는지는 "모름"(null)
 *
 * @param score        0~1. 생육 온도 0.4 + 광도 0.25 + 물주기 0.2 + 분류 0.15 (#507)
 * @param tempOverlap  생육 온도 범위가 겹치면 true (모르면 null)
 * @param lightOverlap 광도 범위가 겹치면 true (모르면 null)
 */
public record EnvironmentMatch(double score, Boolean tempOverlap, Boolean lightOverlap) {

    // 가중치는 평가 세트(#507)로 고른 출발값
    static final double TEMP_WEIGHT = 0.4;
    static final double LIGHT_WEIGHT = 0.25;
    static final double WATER_WEIGHT = 0.2;
    static final double CATEGORY_WEIGHT = 0.15;
    static final double UNKNOWN = 0.5;
    // 생육 온도 범위가 떨어져 있으면 1℃마다 0.1 감점 (16~20℃ vs 21~25℃ → 0.9)
    static final double TEMP_PENALTY_PER_DEGREE = 0.1;
    // 광도 단계(LOW · MEDIUM · HIGH)가 한 단계 떨어질 때마다 0.5 감점
    static final double LIGHT_PENALTY_PER_LEVEL = 0.5;
    // 봄 물주기 단계가 한 단계 떨어질 때마다 0.5 감점
    static final double WATER_PENALTY_PER_LEVEL = 0.5;

    public static EnvironmentMatch of(SpeciesProfile candidate, SpeciesProfile base) {
        Integer tempGap = rangeGap(candidate.growthTempMin(), candidate.growthTempMax(),
                base.growthTempMin(), base.growthTempMax());
        Integer lightGap = rangeGap(lightLevel(candidate.lightMin()), lightLevel(candidate.lightMax()),
                lightLevel(base.lightMin()), lightLevel(base.lightMax()));
        Integer waterGap = levelGap(waterLevel(candidate.waterSpring()), waterLevel(base.waterSpring()));
        Boolean sameCategory = sameCategory(candidate.categoryNames(), base.categoryNames());

        double tempScore = tempGap == null ? UNKNOWN : Math.max(0, 1 - TEMP_PENALTY_PER_DEGREE * tempGap);
        double lightScore = lightGap == null ? UNKNOWN : Math.max(0, 1 - LIGHT_PENALTY_PER_LEVEL * lightGap);
        double waterScore = waterGap == null ? UNKNOWN : Math.max(0, 1 - WATER_PENALTY_PER_LEVEL * waterGap);
        double categoryScore = sameCategory == null ? UNKNOWN : sameCategory ? 1 : 0;
        return new EnvironmentMatch(
                TEMP_WEIGHT * tempScore + LIGHT_WEIGHT * lightScore
                        + WATER_WEIGHT * waterScore + CATEGORY_WEIGHT * categoryScore,
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

    static Integer levelGap(Integer a, Integer b) {
        return a == null || b == null ? null : Math.abs(a - b);
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

    /**
     * 봄 물주기(생육기) 원문 → 젖은 정도 단계. 시드의 원문 네 가지만 알고, 나머지는 모름(null).
     * 0 흙이 물에 잠김 · 1 흙을 촉촉하게 · 2 표면이 마르면 · 3 흙 대부분이 마르면
     */
    static Integer waterLevel(String water) {
        if (water == null) {
            return null;
        }
        if (water.startsWith("항상 흙을 축축하게")) {
            return 0;
        }
        if (water.startsWith("흙을 촉촉하게")) {
            return 1;
        }
        if (water.startsWith("토양 표면이 말랐을때")) {
            return 2;
        }
        if (water.startsWith("화분 흙 대부분 말랐을때")) {
            return 3;
        }
        return null;
    }

    // 분류가 같은지: 꽃을 보는 식물(「잎&꽃보기식물」 · 「꽃보기식물」)인지 아닌지만 본다
    static Boolean sameCategory(String a, String b) {
        if (a == null || b == null) {
            return null;
        }
        return a.contains("꽃보기") == b.contains("꽃보기");
    }
}

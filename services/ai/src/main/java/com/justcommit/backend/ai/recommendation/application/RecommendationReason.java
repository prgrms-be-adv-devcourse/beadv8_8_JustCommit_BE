package com.justcommit.backend.ai.recommendation.application;

import com.justcommit.backend.ai.species.domain.SpeciesProfile;

// 추천 이유 템플릿 (LLM 문장 없음). 품종 프로필로 확인한 공통 조건만 말하고, 값이 없으면 그 조건은 뺀다
final class RecommendationReason {

    private RecommendationReason() {
    }

    static String of(SpeciesProfile base, EnvironmentMatch match) {
        String name = base.name();
        boolean light = Boolean.TRUE.equals(match.lightOverlap());
        boolean temp = Boolean.TRUE.equals(match.tempOverlap());
        if (light && temp) {
            return name + withParticle(name) + " 권장 광도 · 생육 온도가 겹쳐요";
        }
        if (light) {
            return name + withParticle(name) + " 권장 광도가 겹쳐요";
        }
        if (temp) {
            return name + withParticle(name) + " 생육 온도가 겹쳐요";
        }
        // 환경 값으로 확인한 공통점이 없으면 벡터(품종 설명 · 관리 방법 문서)가 비슷하다는 것만 말한다
        return name + withParticle(name) + " 관리 방법이 비슷한 식물이에요";
    }

    // 받침이 있으면 「과」, 없으면 「와」. 한글로 끝나지 않으면 「와」
    static String withParticle(String word) {
        char last = word.charAt(word.length() - 1);
        if (last < '가' || last > '힣') {
            return "와";
        }
        return (last - '가') % 28 == 0 ? "와" : "과";
    }
}

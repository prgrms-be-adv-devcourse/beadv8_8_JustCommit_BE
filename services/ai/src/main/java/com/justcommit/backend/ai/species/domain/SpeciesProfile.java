package com.justcommit.backend.ai.species.domain;

// 품종 프로필 (species_profile 한 행). 농사로 실내정원용 식물 상세로 만든 시드 → ADR 0020
// speciesId는 product species.id와 같은 고정값
public record SpeciesProfile(
        int speciesId,
        String nongsaroNo,
        String name,
        String scientificName,
        String genus,
        String englishName,
        String distributionName,
        String familyName,
        String categoryNames,
        String leafColorNames,
        String difficulty,
        String lightNames,
        String lightMin,
        String lightMax,
        String placementNames,
        String growthTempName,
        Integer growthTempMin,
        Integer growthTempMax,
        Integer winterMinTemp,
        String humidityName,
        String waterSpring,
        String waterSummer,
        String waterAutumn,
        String waterWinter,
        String features,
        String specialCare,
        String advice,
        String pests,
        String toxicity) {
}

package com.justcommit.backend.ai.recommendation.application;

import com.justcommit.backend.ai.recommendation.infrastructure.RecommendationQueryRepository.Candidate;
import com.justcommit.backend.ai.species.domain.SpeciesProfile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 후보 품종 점수 · 순서 (DB · 스프링 없음, 단위 테스트 대상).
 * 최종 점수 = 벡터 유사도 0.6 + 환경 점수 0.4 - 난이도 감점. 가중치는 평가 세트로 조정할 출발값
 */
public final class RecommendationScorer {

    static final double VECTOR_WEIGHT = 0.6;
    static final double ENVIRONMENT_WEIGHT = 0.4;
    // 기준 품종이 모두 쉬움이면 어려움 품종을 조금 뒤로 (사용자가 초보자라고 단정하지 않는 약한 보정)
    static final double HARD_PENALTY = 0.1;

    /**
     * @param bestBase 이 후보와 환경이 가장 잘 맞는 기준 품종 (이유 문장에 씀)
     */
    public record ScoredSpecies(SpeciesProfile species, double score, SpeciesProfile bestBase,
            EnvironmentMatch bestMatch) {
    }

    private RecommendationScorer() {
    }

    /** 후보마다 점수를 매겨 높은 순으로. 프로필이 없는 후보는 뺀다 */
    public static List<ScoredSpecies> score(List<Candidate> candidates, Map<Integer, SpeciesProfile> profiles,
            List<SpeciesProfile> bases) {
        boolean allBasesEasy = !bases.isEmpty() && bases.stream().allMatch(b -> "EASY".equals(b.difficulty()));
        List<ScoredSpecies> scored = new ArrayList<>();
        for (Candidate candidate : candidates) {
            SpeciesProfile species = profiles.get(candidate.speciesId());
            if (species == null) {
                continue;
            }
            double environment = 0;
            SpeciesProfile bestBase = null;
            EnvironmentMatch bestMatch = null;
            for (SpeciesProfile base : bases) {
                EnvironmentMatch match = EnvironmentMatch.of(species, base);
                environment += match.score();
                if (bestMatch == null || match.score() > bestMatch.score()) {
                    bestBase = base;
                    bestMatch = match;
                }
            }
            environment = bases.isEmpty() ? EnvironmentMatch.UNKNOWN : environment / bases.size();
            double penalty = allBasesEasy && "HARD".equals(species.difficulty()) ? HARD_PENALTY : 0;
            double score = VECTOR_WEIGHT * candidate.similarity() + ENVIRONMENT_WEIGHT * environment - penalty;
            scored.add(new ScoredSpecies(species, score, bestBase, bestMatch));
        }
        scored.sort(Comparator.comparingDouble(ScoredSpecies::score).reversed()
                .thenComparingInt(s -> s.species().speciesId()));
        return scored;
    }

    /**
     * 같은 속이 몰리지 않게: 점수 순서를 지키며 속마다 첫 품종을 먼저 세우고, 나머지는 그 뒤에 점수 순으로.
     * 속이 비어 있는 품종은 저마다 다른 속으로 본다
     */
    public static List<ScoredSpecies> diversifyByGenus(List<ScoredSpecies> scored) {
        List<ScoredSpecies> first = new ArrayList<>();
        List<ScoredSpecies> rest = new ArrayList<>();
        Set<String> seenGenus = new HashSet<>();
        for (ScoredSpecies s : scored) {
            String genus = s.species().genus();
            if (genus == null || genus.isBlank() || seenGenus.add(genus)) {
                first.add(s);
            } else {
                rest.add(s);
            }
        }
        first.addAll(rest);
        return first;
    }
}

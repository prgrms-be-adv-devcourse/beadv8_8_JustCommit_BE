package com.justcommit.backend.ai.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.justcommit.backend.ai.recommendation.application.RecommendationScorer.ScoredSpecies;
import com.justcommit.backend.ai.recommendation.infrastructure.RecommendationQueryRepository.Candidate;
import com.justcommit.backend.ai.species.domain.SpeciesProfile;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

// 점수 계산 · 같은 속 분산 · 이유 문장 (DB 없음)
class RecommendationScorerTest {

    private static final String MOIST = "흙을 촉촉하게 유지함(물에 잠기지 않도록 주의)";
    private static final String DRY_SURFACE = "토양 표면이 말랐을때 충분히 관수함";
    private static final String DRY_MOST = "화분 흙 대부분 말랐을때 충분히 관수함";
    private static final String FOLIAGE = "잎보기식물";
    private static final String FLOWER = "잎&꽃보기식물";

    // 몬스테라: 쉬움, 중간~높은 광도, 16~20℃, 봄 물주기 「흙 촉촉」, 잎보기
    private static final SpeciesProfile MONSTERA = species(62, "몬스테라", "Monstera", "EASY", "MEDIUM", "HIGH", 16, 20);

    @Nested
    @DisplayName("환경 점수")
    class Environment {

        @Test
        @DisplayName("생육 온도 · 광도 · 물주기 · 분류가 모두 같으면 1점")
        void allOverlap() {
            EnvironmentMatch match = EnvironmentMatch.of(
                    species(14, "금전수", "Zamioculcas", "EASY", "MEDIUM", "HIGH", 16, 20), MONSTERA);

            assertThat(match.score()).isCloseTo(1.0, within(1e-9));
            assertThat(match.tempOverlap()).isTrue();
            assertThat(match.lightOverlap()).isTrue();
        }

        @Test
        @DisplayName("생육 온도가 1℃ 떨어지면(21~25 vs 16~20) 0점이 아니라 조금만 감점: 0.4 × 0.9 + 나머지 0.6")
        void temperatureGapIsGradual() {
            EnvironmentMatch match = EnvironmentMatch.of(
                    species(28, "데코라고무나무", "Ficus", "EASY", "MEDIUM", "HIGH", 21, 25), MONSTERA);

            assertThat(match.score()).isCloseTo(0.96, within(1e-9));
            assertThat(match.tempOverlap()).isFalse();
        }

        @Test
        @DisplayName("생육 온도가 10℃ 넘게 떨어지면 온도 점수는 0")
        void farTemperatureIsZero() {
            EnvironmentMatch match = EnvironmentMatch.of(
                    species(1, "추운 식물", null, null, "MEDIUM", "HIGH", -5, 5), MONSTERA);

            assertThat(match.score()).isCloseTo(0.6, within(1e-9));
        }

        @Test
        @DisplayName("광도가 한 단계 떨어지면 광도 점수 0.5")
        void lightGap() {
            EnvironmentMatch match = EnvironmentMatch.of(
                    species(2, "그늘 식물", null, null, "LOW", "LOW", 16, 20), MONSTERA);

            assertThat(match.score()).isCloseTo(0.4 + 0.25 * 0.5 + 0.35, within(1e-9));
            assertThat(match.lightOverlap()).isFalse();
        }

        @Test
        @DisplayName("값이 없으면 그 항목은 중립 0.5이고 겹치는지는 모름(null)")
        void missingValueIsNeutral() {
            EnvironmentMatch match = EnvironmentMatch.of(
                    species(104, "소피아 고무나무", "Ficus", null, "LOW", "HIGH", null, null), MONSTERA);

            assertThat(match.score()).isCloseTo(0.4 * 0.5 + 0.6, within(1e-9));
            assertThat(match.tempOverlap()).isNull();
        }

        @Test
        @DisplayName("봄 물주기가 한 단계 다르면 물주기 점수 0.5, 두 단계면 0")
        void waterGap() {
            EnvironmentMatch oneLevel = EnvironmentMatch.of(
                    species(111, "스킨답서스", "Epipremnum", null, "MEDIUM", "HIGH", 16, 20, DRY_SURFACE, FOLIAGE),
                    MONSTERA);
            EnvironmentMatch twoLevels = EnvironmentMatch.of(
                    species(51, "러브체인", "Ceropegia", null, "MEDIUM", "HIGH", 16, 20, DRY_MOST, FOLIAGE),
                    MONSTERA);

            assertThat(oneLevel.score()).isCloseTo(1 - 0.2 * 0.5, within(1e-9));
            assertThat(twoLevels.score()).isCloseTo(1 - 0.2, within(1e-9));
        }

        @Test
        @DisplayName("잎보기와 꽃보기(잎&꽃보기 포함)가 다르면 분류 점수 0")
        void categoryMismatch() {
            EnvironmentMatch match = EnvironmentMatch.of(
                    species(58, "만데빌라", "Mandevilla", null, "MEDIUM", "HIGH", 16, 20, MOIST, FLOWER), MONSTERA);

            assertThat(match.score()).isCloseTo(1 - 0.15, within(1e-9));
        }

        @Test
        @DisplayName("물주기 원문 네 가지만 단계로 보고, 모르는 원문 · 값 없음은 중립")
        void waterLevel() {
            assertThat(EnvironmentMatch.waterLevel("항상 흙을 축축하게 유지함(물에 잠김)")).isZero();
            assertThat(EnvironmentMatch.waterLevel(MOIST)).isEqualTo(1);
            assertThat(EnvironmentMatch.waterLevel(DRY_SURFACE)).isEqualTo(2);
            assertThat(EnvironmentMatch.waterLevel(DRY_MOST)).isEqualTo(3);
            assertThat(EnvironmentMatch.waterLevel("가끔")).isNull();

            EnvironmentMatch unknown = EnvironmentMatch.of(
                    species(108, "수염 틸란드시아", "Tillandsia", null, "MEDIUM", "HIGH", 16, 20, null, null), MONSTERA);
            assertThat(unknown.score()).isCloseTo(0.65 + 0.35 * 0.5, within(1e-9));
        }
    }

    @Nested
    @DisplayName("최종 점수")
    class Score {

        @Test
        @DisplayName("벡터 0.6 + 환경 0.4로 높은 순, 기준 품종 중 환경이 가장 잘 맞는 것을 이유에 씀")
        void weightedAndSorted() {
            SpeciesProfile zz = species(14, "금전수", "Zamioculcas", "EASY", "MEDIUM", "HIGH", 16, 20);
            SpeciesProfile ficus = species(28, "데코라고무나무", "Ficus", "EASY", "MEDIUM", "HIGH", 21, 25);
            SpeciesProfile sansevieria = species(93, "산세베리아", "Sansevieria", "EASY", "LOW", "HIGH", 21, 25);

            List<ScoredSpecies> scored = RecommendationScorer.score(
                    List.of(new Candidate(28, 0.95), new Candidate(14, 0.90)),
                    Map.of(14, zz, 28, ficus), List.of(MONSTERA, sansevieria));

            // 금전수: 0.6×0.90 + 0.4×avg(1.0, 0.96) / 데코라: 0.6×0.95 + 0.4×avg(0.96, 1.0)
            assertThat(scored).extracting(s -> s.species().speciesId()).containsExactly(28, 14);
            assertThat(scored.get(0).score()).isCloseTo(0.6 * 0.95 + 0.4 * 0.98, within(1e-9));
            assertThat(scored.get(0).bestBase().name()).isEqualTo("산세베리아");
            assertThat(scored.get(1).bestBase().name()).isEqualTo("몬스테라");
        }

        @Test
        @DisplayName("기준 품종이 모두 쉬움이면 어려움 품종만 0.1 감점, 하나라도 쉬움이 아니면 감점 없음")
        void hardPenaltyOnlyWhenAllBasesEasy() {
            SpeciesProfile hard = species(5, "어려운 식물", "A", "HARD", "MEDIUM", "HIGH", 16, 20);
            SpeciesProfile normalBase = species(36, "드라세나 와네끼", "Dracaena", "NORMAL", "MEDIUM", "HIGH", 16, 20);
            List<Candidate> candidates = List.of(new Candidate(5, 0.9));

            double easyOnly = RecommendationScorer.score(candidates, Map.of(5, hard), List.of(MONSTERA)).get(0).score();
            double mixed = RecommendationScorer.score(candidates, Map.of(5, hard), List.of(MONSTERA, normalBase))
                    .get(0).score();

            assertThat(easyOnly).isCloseTo(0.6 * 0.9 + 0.4 - 0.1, within(1e-9));
            assertThat(mixed).isCloseTo(0.6 * 0.9 + 0.4, within(1e-9));
        }

        @Test
        @DisplayName("프로필이 없는 후보는 뺀다")
        void skipsUnknownSpecies() {
            assertThat(RecommendationScorer.score(List.of(new Candidate(999, 0.9)), Map.of(), List.of(MONSTERA)))
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("같은 속 분산: 속마다 첫 품종을 먼저, 같은 속 두 번째부터는 뒤로 (빼지는 않음)")
    void diversifyByGenus() {
        List<ScoredSpecies> scored = List.of(
                scoredOf(species(28, "데코라고무나무", "Ficus", null, null, null, null, null), 0.9),
                scoredOf(species(25, "대만고무나무", "Ficus", null, null, null, null, null), 0.8),
                scoredOf(species(14, "금전수", "Zamioculcas", null, null, null, null, null), 0.7),
                scoredOf(species(7, "속 없음", null, null, null, null, null, null), 0.6));

        assertThat(RecommendationScorer.diversifyByGenus(scored))
                .extracting(s -> s.species().speciesId())
                .containsExactly(28, 14, 7, 25);
    }

    @Nested
    @DisplayName("이유 문장")
    class Reason {

        @Test
        @DisplayName("겹치는 조건만 말한다")
        void onlyMatchedConditions() {
            assertThat(RecommendationReason.of(MONSTERA, new EnvironmentMatch(1, true, true)))
                    .isEqualTo("몬스테라와 권장 광도 · 생육 온도가 겹쳐요");
            assertThat(RecommendationReason.of(MONSTERA, new EnvironmentMatch(0.94, false, true)))
                    .isEqualTo("몬스테라와 권장 광도가 겹쳐요");
            assertThat(RecommendationReason.of(MONSTERA, new EnvironmentMatch(0.8, true, null)))
                    .isEqualTo("몬스테라와 생육 온도가 겹쳐요");
            assertThat(RecommendationReason.of(MONSTERA, new EnvironmentMatch(0.5, null, null)))
                    .isEqualTo("몬스테라와 관리 방법이 비슷한 식물이에요");
        }

        @Test
        @DisplayName("받침이 있으면 「과」, 없거나 한글이 아니면 「와」")
        void particle() {
            assertThat(RecommendationReason.withParticle("금전수")).isEqualTo("와");
            assertThat(RecommendationReason.withParticle("행운목")).isEqualTo("과");
            assertThat(RecommendationReason.withParticle("드라세나 '자바'")).isEqualTo("와");
        }
    }

    private static ScoredSpecies scoredOf(SpeciesProfile species, double score) {
        return new ScoredSpecies(species, score, MONSTERA, new EnvironmentMatch(1, true, true));
    }

    // 물주기 · 분류는 몬스테라와 같게 (흙 촉촉 · 잎보기)
    static SpeciesProfile species(int id, String name, String genus, String difficulty, String lightMin,
            String lightMax, Integer tempMin, Integer tempMax) {
        return species(id, name, genus, difficulty, lightMin, lightMax, tempMin, tempMax, MOIST, FOLIAGE);
    }

    static SpeciesProfile species(int id, String name, String genus, String difficulty, String lightMin,
            String lightMax, Integer tempMin, Integer tempMax, String waterSpring, String categoryNames) {
        return new SpeciesProfile(id, "0", name, null, genus, null, null, null, categoryNames, null, difficulty, null,
                lightMin, lightMax, null, null, tempMin, tempMax, null, null, waterSpring, null, null, null,
                null, null, null, null, null);
    }
}

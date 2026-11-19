package com.justcommit.backend.ai.recommendation.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.justcommit.backend.ai.recommendation.application.CartRecommendationService;
import com.justcommit.backend.ai.recommendation.application.RecommendationScorer;
import com.justcommit.backend.ai.recommendation.application.RecommendationScorer.ScoredSpecies;
import com.justcommit.backend.ai.recommendation.infrastructure.RecommendationQueryRepository.Candidate;
import com.justcommit.backend.ai.species.domain.SpeciesProfile;
import com.justcommit.backend.ai.species.infrastructure.SpeciesProfileRepository;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 추천 평가 세트 적중률 (#507, docs/flows/search-recommend.md 「추천」).
 *
 * <p>정답 쌍(eval-pairs.csv)마다 기준 품종 하나를 장바구니에 담고, 모든 품종에 판매 중 상품이 1개씩 있다고 보고
 * 서비스와 같은 순서(벡터 후보 {@link CartRecommendationService#CANDIDATE_LIMIT}개 → 점수 → 같은 속 분산)로
 * 상위 10개 품종을 고른다. 정답 품종이 그 안에 있으면 적중.
 *
 * <p>유사도는 실제 OpenAI 임베딩으로 내보낸 eval-similarities.csv({@link EvalSimilarityExportTest})를 쓰고,
 * 파일이 없으면 건너뛴다. 결과 표는 build/recommend-eval.md에 쓴다.
 */
@Testcontainers
class RecommendationEvaluationTest {

    private static final Path SIMILARITIES = Path.of(EvalSimilarityExportTest.OUTPUT);
    private static final Path REPORT = Path.of("build/recommend-eval.md");
    private static final int TOP = 10;
    // 지금 규칙의 적중 수 (#507: 고치기 전 2 → 물주기 · 분류 추가 뒤 5). 점수 규칙을 바꿔 이보다 줄면 실패한다
    private static final int MIN_HITS = 5;

    private static Map<Integer, SpeciesProfile> profiles;

    @BeforeAll
    static void loadProfiles() {
        String url = SharedPostgres.createDatabase();
        Flyway.configure()
                .dataSource(url, SharedPostgres.container().getUsername(), SharedPostgres.container().getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        dataSource.setUser(SharedPostgres.container().getUsername());
        dataSource.setPassword(SharedPostgres.container().getPassword());
        profiles = new SpeciesProfileRepository(JdbcClient.create(dataSource)).findAll().stream()
                .collect(Collectors.toMap(SpeciesProfile::speciesId, Function.identity()));
    }

    @Test
    @DisplayName("평가 세트의 품종 ID · 이름이 품종 프로필 시드와 같다")
    void pairsMatchSeed() {
        List<EvalPair> pairs = EvalPair.load();

        assertThat(pairs).hasSizeBetween(15, 20);
        for (EvalPair pair : pairs) {
            assertThat(profiles.get(pair.baseId()).name()).isEqualTo(pair.baseName());
            assertThat(profiles.get(pair.answerId()).name()).isEqualTo(pair.answerName());
        }
    }

    @Test
    @DisplayName("정답 짝이 Top-10에 들어오는 수가 기준 이상이다")
    void hitRate() throws IOException {
        Assumptions.assumeTrue(Files.exists(SIMILARITIES), "eval-similarities.csv가 없어 건너뜀 (EvalSimilarityExportTest)");
        Map<Integer, List<Candidate>> similarities = loadSimilarities();

        List<String> report = new ArrayList<>();
        report.add("| 기준 | 정답 | 벡터 순위 | 최종 순위 (후보 " + CartRecommendationService.CANDIDATE_LIMIT + "개) | 최종 순위 (후보 전체) |");
        report.add("|---|---|---|---|---|");
        int hits = 0;
        int hitsAllCandidates = 0;
        for (EvalPair pair : EvalPair.load()) {
            List<Candidate> byVector = similarities.get(pair.baseId());
            int vectorRank = rankOf(byVector.stream().map(Candidate::speciesId).toList(), pair.answerId());
            int limitedRank = rankOf(recommend(pair.baseId(), byVector, CartRecommendationService.CANDIDATE_LIMIT),
                    pair.answerId());
            int allRank = rankOf(recommend(pair.baseId(), byVector, byVector.size()), pair.answerId());
            hits += limitedRank > 0 && limitedRank <= TOP ? 1 : 0;
            hitsAllCandidates += allRank > 0 && allRank <= TOP ? 1 : 0;
            report.add("| %s | %s | %d | %s | %d |".formatted(pair.baseName(), pair.answerName(), vectorRank,
                    limitedRank > 0 ? String.valueOf(limitedRank) : "후보 밖", allRank));
        }
        report.add("");
        report.add("Top-%d 적중: 후보 %d개 %d / %d · 후보 전체 %d / %d".formatted(TOP,
                CartRecommendationService.CANDIDATE_LIMIT, hits, EvalPair.load().size(),
                hitsAllCandidates, EvalPair.load().size()));
        Files.createDirectories(REPORT.getParent());
        Files.write(REPORT, report);
        report.forEach(System.out::println);

        assertThat(hits).isGreaterThanOrEqualTo(MIN_HITS);
    }

    // 서비스와 같은 순서: 벡터 상위 limit개 → 점수 → 같은 속 분산. 품종 ID 순서
    private List<Integer> recommend(int baseId, List<Candidate> byVector, int limit) {
        List<ScoredSpecies> ordered = RecommendationScorer.diversifyByGenus(RecommendationScorer.score(
                byVector.subList(0, Math.min(limit, byVector.size())), profiles, List.of(profiles.get(baseId))));
        return ordered.stream().map(s -> s.species().speciesId()).toList();
    }

    // 1부터 센 순위, 없으면 0
    private static int rankOf(List<Integer> ids, int speciesId) {
        return ids.indexOf(speciesId) + 1;
    }

    // 기준 품종마다 유사도 높은 순 후보 목록
    private static Map<Integer, List<Candidate>> loadSimilarities() throws IOException {
        Map<Integer, List<Candidate>> byBase = new HashMap<>();
        Files.readAllLines(SIMILARITIES).stream().skip(1).filter(line -> !line.isBlank()).forEach(line -> {
            String[] c = line.split(",");
            byBase.computeIfAbsent(Integer.parseInt(c[0]), id -> new ArrayList<>())
                    .add(new Candidate(Integer.parseInt(c[1]), Double.parseDouble(c[2])));
        });
        byBase.values().forEach(list -> list.sort(Comparator.comparingDouble(Candidate::similarity).reversed()
                .thenComparingInt(Candidate::speciesId)));
        return byBase;
    }
}

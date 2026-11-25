package com.justcommit.backend.ai.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.justcommit.backend.ai.common.StageTimers;
import com.justcommit.backend.ai.search.application.QueryEmbedder;
import com.justcommit.backend.ai.search.application.SpeciesSearchService;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 품종 의미 검색 외부 API: 질의 임베딩은 가짜(문장마다 고정 벡터), 유사도 검색은 Testcontainers pgvector로 실제 쿼리.
// 품종 프로필은 Flyway 시드(V3): 산세베리아 93 · 금전수 14 · 몬스테라 62 · 스킨답서스 111
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SpeciesSearchIntegrationTest {

    private static final int DIMENSIONS = 1536;
    private static final String FAIL = "임베딩이 실패하는 문장";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
        registry.add("jwt.public-key", SpeciesSearchIntegrationTest::newPublicKey);
        registry.add("internal-api.key", () -> "test-internal-key");
        registry.add("spring.ai.openai.api-key", () -> "test-key");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    // OpenAI 대신: 어떤 문장이든 첫 칸만 1인 벡터, FAIL 문장은 임베딩 실패
    @TestConfiguration
    static class FakeEmbedderConfig {
        @Bean
        @Primary
        FakeQueryEmbedder fakeQueryEmbedder() {
            return new FakeQueryEmbedder();
        }
    }

    static class FakeQueryEmbedder implements QueryEmbedder {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public float[] embed(String query) {
            calls.incrementAndGet();
            if (query.equals(FAIL)) {
                throw new QueryEmbeddingException("시간 초과", new RuntimeException("timeout"));
            }
            float[] v = new float[DIMENSIONS];
            v[0] = 1f;
            return v;
        }
    }

    @LocalServerPort int port;
    @Autowired JdbcClient jdbcClient;
    @Autowired FakeQueryEmbedder embedder;
    @Autowired MeterRegistry meterRegistry;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        jdbcClient.sql("DELETE FROM product_snapshot").update();
        jdbcClient.sql("DELETE FROM species_vector_store").update();
        embedder.calls.set(0);

        // 질의 벡터 (1, 0)과의 유사도: 산세베리아 1.0 > 금전수 0.994 > 몬스테라 0.707 > 스킨답서스 0 (최소 0.2 미만)
        vector(93, 1.0, 0.0);
        vector(14, 0.9, 0.1);
        vector(62, 0.5, 0.5);
        vector(111, 0.0, 1.0);

        snapshot(5001, 93, "ACTIVE", false);
        snapshot(5002, 93, "ACTIVE", true);       // 숨김
        snapshot(5003, 93, "SOLD_OUT", false);    // 판매 완료
        snapshot(5004, 93, "ACTIVE", false);
        snapshot(5005, 93, "ACTIVE", false);
        snapshot(5006, 93, "ACTIVE", false);
        snapshot(1001, 62, "PAYMENT_PENDING", false);
        snapshot(4001, 111, "ACTIVE", false);
    }

    @Test
    @DisplayName("유사도 순 품종 · 이름 · 유사도와 품종마다 판매 중 상품을 최신 3개까지 (로그인 없이)")
    void searchesSimilarSpecies() throws Exception {
        HttpResponse<String> response = search("햇빛 적은 방에서 키우기 쉬운 식물", 5);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode items = json.readTree(response.body()).get("data").get("items");
        assertThat(items.valueStream().map(i -> i.get("speciesId").asInt()).toList()).containsExactly(93, 14, 62);
        assertThat(items.get(0).get("name").asString()).isEqualTo("산세베리아");
        assertThat(items.get(0).get("similarity").asDouble()).isEqualTo(1.0);
        assertThat(items.get(2).get("similarity").asDouble()).isEqualTo(0.707);
        // 숨김 · 판매 완료는 빼고 최신 3개
        assertThat(productIds(items.get(0))).containsExactly(5006L, 5005L, 5004L);
        // 판매 중 상품이 없는 품종도 품종은 보여 준다 (결제 대기는 판매 중이 아님)
        assertThat(productIds(items.get(1))).isEmpty();
        assertThat(productIds(items.get(2))).isEmpty();
    }

    @Test
    @DisplayName("[#634] 질문 임베딩 · 벡터 검색 · 상품 조회 · 전체를 단계별 Timer로, 임베딩이 실패하면 임베딩 · 전체가 실패로")
    void recordsStageTimers() throws Exception {
        List<String> stages = List.of(SpeciesSearchService.STAGE_EMBEDDING, SpeciesSearchService.STAGE_VECTOR_SEARCH,
                SpeciesSearchService.STAGE_PRODUCT_LOOKUP, SpeciesSearchService.STAGE_TOTAL);
        Map<String, Long> before = counts(stages);

        assertThat(search("햇빛 적은 방에서 키우기 쉬운 식물", 5).statusCode()).isEqualTo(200);

        for (String stage : stages) {
            assertThat(count(stage)).as(stage).isEqualTo(before.get(stage) + 1);
        }

        Map<String, Long> beforeFailure = counts(stages);
        long embeddingFailures = failures(SpeciesSearchService.STAGE_EMBEDDING);
        long totalFailures = failures(SpeciesSearchService.STAGE_TOTAL);
        assertThat(search(FAIL, 5).statusCode()).isEqualTo(503);

        assertThat(failures(SpeciesSearchService.STAGE_EMBEDDING)).isEqualTo(embeddingFailures + 1);
        assertThat(failures(SpeciesSearchService.STAGE_TOTAL)).isEqualTo(totalFailures + 1);
        assertThat(counts(stages)).isEqualTo(beforeFailure);
    }

    private Map<String, Long> counts(List<String> stages) {
        Map<String, Long> counts = new HashMap<>();
        stages.forEach(stage -> counts.put(stage, count(stage)));
        return counts;
    }

    private long count(String stage) {
        return meterRegistry.get(SpeciesSearchService.METRIC).tag("stage", stage)
                .tag("outcome", StageTimers.SUCCESS).timer().count();
    }

    // 실패 Timer는 처음 실패할 때 생기므로 없으면 0건
    private long failures(String stage) {
        Timer timer = meterRegistry.find(SpeciesSearchService.METRIC).tag("stage", stage)
                .tag("outcome", StageTimers.FAILURE).timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    @DisplayName("size만큼만, 문장 앞뒤 공백은 지운다")
    void respectsSize() throws Exception {
        JsonNode items = json.readTree(search("  그늘에서 잘 자라는 식물  ", 1).body()).get("data").get("items");

        assertThat(items.valueStream().map(i -> i.get("speciesId").asInt()).toList()).containsExactly(93);
    }

    @Test
    @DisplayName("가까운 품종이 최소 유사도를 넘지 않으면 빈 목록 (200)")
    void emptyWhenNothingSimilar() throws Exception {
        jdbcClient.sql("DELETE FROM species_vector_store WHERE id <> 111").update();

        HttpResponse<String> response = search("아무 문장", 5);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("data").get("items").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("문장이 없거나 2자 미만 · 100자 초과, size 1~10 밖 · 숫자 아님이면 400이고 OpenAI를 부르지 않는다")
    void rejectsInvalidRequest() throws Exception {
        assertThat(send("")).isEqualTo(400);
        assertThat(search(" a ", 5).statusCode()).isEqualTo(400);
        assertThat(search("가".repeat(101), 5).statusCode()).isEqualTo(400);
        assertThat(search("가".repeat(100), 0).statusCode()).isEqualTo(400);
        assertThat(search("가".repeat(100), 11).statusCode()).isEqualTo(400);
        assertThat(json.readTree(search(" a ", 5).body()).get("code").asString()).isEqualTo("INVALID_SEARCH_QUERY");
        HttpResponse<String> notNumber = http.send(HttpRequest.newBuilder(
                URI.create(base() + "?query=%EC%8B%9D%EB%AC%BC%20%EC%B6%94%EC%B2%9C&size=abc")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(notNumber.statusCode()).isEqualTo(400);
        assertThat(json.readTree(notNumber.body()).get("code").asString()).isEqualTo("INVALID_SEARCH_SIZE");
        assertThat(embedder.calls.get()).isZero();
    }

    @Test
    @DisplayName("질의 임베딩이 실패하면 503 SEMANTIC_SEARCH_UNAVAILABLE, 다른 API(추천)는 그대로")
    void unavailableWhenEmbeddingFails() throws Exception {
        HttpResponse<String> response = search(FAIL, 5);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("SEMANTIC_SEARCH_UNAVAILABLE");

        HttpRequest recommend = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/internal/v1/recommendations/cart"))
                .header("Content-Type", "application/json")
                .header("X-Internal-Key", "test-internal-key")
                .POST(HttpRequest.BodyPublishers.ofString("{\"cartProductIds\":[5001],\"size\":5}"))
                .build();
        assertThat(http.send(recommend, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> search(String query, int size) throws Exception {
        String q = URLEncoder.encode(query, StandardCharsets.UTF_8);
        return http.send(HttpRequest.newBuilder(URI.create(base() + "?query=" + q + "&size=" + size)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private int send(String queryString) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + queryString)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private String base() {
        return "http://localhost:" + port + "/api/v1/speciesSearches";
    }

    private static List<Long> productIds(JsonNode item) {
        return item.get("productIds").valueStream().map(JsonNode::asLong).toList();
    }

    // 앞 두 칸만 값이 있는 1536차원 벡터
    private void vector(int speciesId, double x, double y) {
        String values = IntStream.range(0, DIMENSIONS)
                .mapToObj(i -> i == 0 ? String.valueOf(x) : i == 1 ? String.valueOf(y) : "0")
                .collect(Collectors.joining(",", "[", "]"));
        jdbcClient.sql("INSERT INTO species_vector_store (id, content, metadata, embedding) "
                        + "VALUES (:id, 'test', '{}'::json, CAST(:v AS vector))")
                .param("id", speciesId)
                .param("v", values)
                .update();
    }

    private void snapshot(long productId, long speciesId, String status, boolean hidden) {
        jdbcClient.sql("INSERT INTO product_snapshot (product_id, species_id, seller_id, status, hidden, product_version) "
                        + "VALUES (:p, :s, 7, :status, :hidden, 1)")
                .param("p", productId)
                .param("s", speciesId)
                .param("status", status)
                .param("hidden", hidden)
                .update();
    }

    private static String newPublicKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

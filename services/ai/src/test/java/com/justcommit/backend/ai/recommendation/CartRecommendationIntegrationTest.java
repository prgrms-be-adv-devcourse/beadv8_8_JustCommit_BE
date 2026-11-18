package com.justcommit.backend.ai.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 장바구니 추천 내부 API: 스냅샷 · 품종 벡터를 직접 넣고(임베딩 모델 호출 없음) 응답 형식 · 순서 · 거르기를 본다.
// 품종 프로필은 Flyway 시드(V3) 그대로: 몬스테라 62 · 금전수 14 · 데코라고무나무 28 · 대만고무나무 25(둘 다 Ficus) ·
// 스킨답서스 111 · 산세베리아 93
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CartRecommendationIntegrationTest {

    private static final String INTERNAL_KEY = "test-internal-key";
    private static final int DIMENSIONS = 1536;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.public-key", CartRecommendationIntegrationTest::newPublicKey);
        registry.add("internal-api.key", () -> INTERNAL_KEY);
        registry.add("spring.ai.openai.api-key", () -> "test-key");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired JdbcClient jdbcClient;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        jdbcClient.sql("DELETE FROM product_snapshot").update();
        jdbcClient.sql("DELETE FROM species_vector_store").update();

        // 몬스테라(기준)와의 코사인 유사도: 산세베리아 > 금전수 > 데코라 > 대만 > 스킨답서스
        vector(62, 1.0, 0.0);
        vector(93, 0.95, 0.05);
        vector(14, 0.9, 0.1);
        vector(28, 0.85, 0.15);
        vector(25, 0.8, 0.2);
        vector(111, 0.5, 0.5);

        snapshot(1001, 62, "ACTIVE", false);   // 장바구니 상품 (몬스테라)
        snapshot(1002, 62, "ACTIVE", false);   // 기준 품종과 같은 품종 → 추천 안 함
        snapshot(9001, 62, "SOLD_OUT", false); // 최근 구매 상품 (판매 완료)
        snapshot(2001, 14, "ACTIVE", false);
        snapshot(2002, 14, "ACTIVE", false);
        snapshot(2003, 14, "PAYMENT_PENDING", false);
        snapshot(3001, 28, "ACTIVE", false);
        snapshot(3101, 25, "ACTIVE", false);
        snapshot(4001, 111, "ACTIVE", false);
        snapshot(5001, 93, "SOLD_OUT", false); // 산세베리아는 판매 중 상품이 없어 후보에서 빠짐
        snapshot(5002, 93, "ACTIVE", true);
    }

    @Test
    @DisplayName("점수 순 · 같은 속은 뒤로 · 품종을 돌아가며 판매 중 상품을 product_id 내림차순으로, 이유 문장과 함께")
    void recommendsInOrder() throws Exception {
        long start = System.nanoTime();
        HttpResponse<String> response = recommend("""
                {"cartProductIds":[1001],"purchasedProductIds":[],"size":10}
                """);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode items = json.readTree(response.body()).get("data").get("items");
        // 금전수 → 데코라(Ficus 첫째) → 스킨답서스 → 대만(Ficus 둘째) → 금전수 두 번째 상품
        assertThat(productIds(items)).containsExactly(2002L, 3001L, 4001L, 3101L, 2001L);
        assertThat(items.get(0).get("reason").asString()).isEqualTo("몬스테라와 권장 광도 · 생육 온도가 겹쳐요");
        assertThat(items.get(1).get("reason").asString()).isEqualTo("몬스테라와 권장 광도가 겹쳐요");
        System.out.println("장바구니 추천 응답 시간(첫 요청 포함): " + elapsedMs + "ms");
    }

    @Test
    @DisplayName("벡터가 없는 기준 품종(행운목)은 환경 점수 · 이유 문장에서도 빠지고 몬스테라만 기준이 된다")
    void baseSpeciesWithoutVectorIsIgnored() throws Exception {
        // 행운목 203: 21~25℃ · 중간~높은 광도라 기준에 남으면 데코라고무나무의 이유가 「행운목과 …」로 바뀜
        snapshot(6001, 203, "ACTIVE", false);

        HttpResponse<String> response = recommend("""
                {"cartProductIds":[1001,6001],"purchasedProductIds":[],"size":10}
                """);

        JsonNode items = json.readTree(response.body()).get("data").get("items");
        assertThat(productIds(items)).containsExactly(2002L, 3001L, 4001L, 3101L, 2001L);
        assertThat(items.get(1).get("reason").asString()).isEqualTo("몬스테라와 권장 광도가 겹쳐요");
    }

    @Test
    @DisplayName("기준 품종에 벡터가 하나도 없으면 빈 목록")
    void emptyWhenNoBaseVector() throws Exception {
        snapshot(6001, 203, "ACTIVE", false);

        HttpResponse<String> response = recommend("""
                {"cartProductIds":[6001],"purchasedProductIds":[],"size":10}
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("data").get("items").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("받은 size만큼만 돌려준다")
    void respectsSize() throws Exception {
        HttpResponse<String> response = recommend("""
                {"cartProductIds":[1001],"purchasedProductIds":[],"size":3}
                """);

        assertThat(productIds(json.readTree(response.body()).get("data").get("items")))
                .containsExactly(2002L, 3001L, 4001L);
    }

    @Test
    @DisplayName("장바구니가 비어도 최근 구매(판매 완료) 상품의 품종으로 추천한다")
    void recommendsFromPurchasedOnly() throws Exception {
        HttpResponse<String> response = recommend("""
                {"cartProductIds":[],"purchasedProductIds":[9001],"size":2}
                """);

        assertThat(productIds(json.readTree(response.body()).get("data").get("items")))
                .containsExactly(2002L, 3001L);
    }

    @Test
    @DisplayName("두 목록이 비었거나 null이거나 스냅샷에 없는 상품뿐이면 빈 목록 (200)")
    void emptyWhenNothingToBaseOn() throws Exception {
        for (String body : List.of(
                "{\"cartProductIds\":[],\"purchasedProductIds\":[],\"size\":10}",
                "{\"size\":10}",
                "{\"cartProductIds\":[77777],\"size\":10}")) {
            HttpResponse<String> response = recommend(body);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.readTree(response.body()).get("data").get("items").isEmpty()).isTrue();
        }
    }

    @Test
    @DisplayName("스냅샷이 비어 있어도 정상 응답(빈 목록)")
    void emptySnapshot() throws Exception {
        jdbcClient.sql("DELETE FROM product_snapshot").update();

        HttpResponse<String> response = recommend("""
                {"cartProductIds":[1001],"purchasedProductIds":[9001],"size":10}
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("data").get("items").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("size 1~20 밖 · null이나 0 이하 ID · 목록 100개 초과는 400")
    void rejectsInvalidRequest() throws Exception {
        String tooMany = IntStream.rangeClosed(1, 101).mapToObj(String::valueOf).collect(Collectors.joining(","));
        for (String body : List.of(
                "{\"cartProductIds\":[1001],\"size\":0}",
                "{\"cartProductIds\":[1001],\"size\":21}",
                "{\"cartProductIds\":[null],\"size\":10}",
                "{\"cartProductIds\":[0],\"size\":10}",
                "{\"cartProductIds\":[" + tooMany + "],\"size\":10}")) {
            assertThat(recommend(body).statusCode()).as(body).isEqualTo(400);
        }
    }

    @Test
    @DisplayName("내부 키가 없으면 401")
    void rejectsWithoutInternalKey() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"cartProductIds\":[1001],\"size\":10}"))
                .build();

        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> recommend(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri())
                .header("Content-Type", "application/json")
                .header("X-Internal-Key", INTERNAL_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri() {
        return URI.create("http://localhost:" + port + "/internal/v1/recommendations/cart");
    }

    private static List<Long> productIds(JsonNode items) {
        return items.valueStream().map(item -> item.get("productId").asLong()).toList();
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

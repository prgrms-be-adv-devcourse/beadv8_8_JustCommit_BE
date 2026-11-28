package com.justcommit.backend.ai.chatbot;

import static com.justcommit.backend.ai.support.ProductEvents.v3;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.justcommit.backend.ai.chatbot.application.AnswerGenerator;
import com.justcommit.backend.ai.chatbot.application.ChatPrompt;
import com.justcommit.backend.ai.chatbot.application.ChatTurn;
import com.justcommit.backend.ai.chatbot.application.ChatbotService;
import com.justcommit.backend.ai.common.StageTimers;
import com.justcommit.backend.ai.product.infrastructure.messaging.ProductEventListener;
import com.justcommit.backend.ai.search.application.QueryEmbedder;
import com.justcommit.backend.ai.support.KeywordEmbeddingModel;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.common.security.JwtVerifier;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 챗봇 메시지 API (#300 · #302). OpenAI 임베딩은 낱말 기반 스텁, LLM은 가짜 구현.
 * 키워드가 겹치지 않는 질문(「어두운」)으로 의미가 맞는 상품(「그늘」)을 찾고, 판매 중이 아닌 상품은 빼는지 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ChatbotIntegrationTest {

    // 「기타」 품종: 품종 프로필이 없어 상품 문서에 품종 글이 섞이지 않음 (스텁 낱말 판정을 흔들지 않게)
    private static final long OTHER_SPECIES = 218L;
    private static final String MEMBER_A = "token-a";
    private static final String MEMBER_B = "token-b";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
        registry.add("jwt.public-key", () -> "unused");
        registry.add("spring.ai.openai.api-key", () -> "test-key");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        KeywordEmbeddingModel keywordEmbeddingModel() {
            return new KeywordEmbeddingModel();
        }

        // 질문 임베딩(품종 의미 검색과 같은 QueryEmbedder)도 같은 낱말 스텁으로, 실패 흉내도 같이
        @Bean
        @Primary
        QueryEmbedder keywordQueryEmbedder(KeywordEmbeddingModel model) {
            return (query, scope) -> {
                if (model.failing.get()) {
                    throw new QueryEmbedder.QueryEmbeddingException("임베딩 API 실패 (테스트)", null);
                }
                return model.vectorOf(query);
            };
        }

        @Bean
        @Primary
        FakeAnswerGenerator fakeAnswerGenerator() {
            return new FakeAnswerGenerator();
        }
    }

    // 받은 요청을 남기고, 실패를 흉내 낼 수 있는 LLM
    static class FakeAnswerGenerator implements AnswerGenerator {
        final List<ChatPrompt> prompts = new CopyOnWriteArrayList<>();
        final AtomicBoolean failing = new AtomicBoolean();

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public String answer(ChatPrompt prompt) {
            prompts.add(prompt);
            if (failing.get()) {
                throw new IllegalStateException("LLM 실패 (테스트)");
            }
            return "그늘 몬스테라를 추천해요.";
        }
    }

    @MockitoBean JwtVerifier jwtVerifier;
    @LocalServerPort int port;
    @Autowired ProductEventListener listener;
    @Autowired KeywordEmbeddingModel embeddingModel;
    @Autowired FakeAnswerGenerator answerGenerator;
    @Autowired JdbcClient jdbcClient;
    @Autowired MeterRegistry meterRegistry;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        given(jwtVerifier.parseAccessToken(MEMBER_A)).willReturn(new AuthMember(1L, "MEMBER"));
        given(jwtVerifier.parseAccessToken(MEMBER_B)).willReturn(new AuthMember(2L, "MEMBER"));
        jdbcClient.sql("DELETE FROM chat_conversation").update();
        jdbcClient.sql("DELETE FROM product_vector_store").update();
        jdbcClient.sql("DELETE FROM product_snapshot").update();
        jdbcClient.sql("DELETE FROM species_vector_store").update();
        embeddingModel.failing.set(false);
        answerGenerator.failing.set(false);
        answerGenerator.prompts.clear();

        listener.on(v3("ProductRegistered", 1L, OTHER_SPECIES, "ACTIVE", false, 0, "그늘 몬스테라", "그늘에서도 잘 커요"));
        listener.on(v3("ProductRegistered", 2L, OTHER_SPECIES, "ACTIVE", false, 0, "선인장 모음", "건조하게 키워요"));
        listener.on(v3("ProductRegistered", 3L, OTHER_SPECIES, "ACTIVE", false, 0, "그늘 스킨답서스", "그늘 OK"));
        listener.on(v3("ProductStatusChanged", 3L, OTHER_SPECIES, "SOLD_OUT", false, 1, "그늘 스킨답서스", "그늘 OK"));
        listener.on(v3("ProductRegistered", 4L, OTHER_SPECIES, "ACTIVE", false, 0, "그늘 고사리", "그늘"));
        listener.on(v3("ProductStatusChanged", 4L, OTHER_SPECIES, "ACTIVE", true, 1, "그늘 고사리", "그늘"));
    }

    @Test
    @DisplayName("키워드가 달라도 뜻이 맞는 판매 중 상품만 카드로 오고, 판매 완료 · 숨김 · 관계없는 상품은 빠진다")
    void findsSemanticallyMatchingActiveProducts() throws Exception {
        HttpResponse<String> response = send(MEMBER_A, null, "어두운 방에서 키울 식물 있어?");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = json.readTree(response.body()).get("data");
        assertThat(data.get("conversationId").asString()).isNotBlank();
        assertThat(data.get("answer").asString()).isEqualTo("그늘 몬스테라를 추천해요.");
        assertThat(data.get("answerGenerated").asBoolean()).isTrue();
        assertThat(productIds(data)).containsExactly(1L);
        JsonNode card = data.get("products").get(0);
        assertThat(card.get("title").asString()).isEqualTo("그늘 몬스테라");
        assertThat(card.get("price").asInt()).isEqualTo(15000);
        assertThat(card.get("thumbnailUrl").asString()).isEqualTo("https://img.test/1.jpg");

        ChatPrompt prompt = answerGenerator.prompts.getFirst();
        assertThat(prompt.history()).isEmpty();
        assertThat(prompt.userMessage()).contains("그늘 몬스테라", "질문: 어두운 방에서 키울 식물 있어?")
                .doesNotContain("그늘 스킨답서스", "그늘 고사리");
    }

    @Test
    @DisplayName("[#634] 단계별 Timer: 새 대화는 기록 읽기 없이 임베딩 · 상품 · 품종 검색 · 답변 생성 · 저장 · 전체, 이어 묻기는 기록 읽기도")
    void recordsStageTimers() throws Exception {
        List<String> everyTurn = List.of(ChatbotService.STAGE_EMBEDDING, ChatbotService.STAGE_PRODUCT_SEARCH,
                ChatbotService.STAGE_SPECIES_SEARCH, ChatbotService.STAGE_SEARCH, ChatbotService.STAGE_ANSWER, ChatbotService.STAGE_SAVE,
                ChatbotService.STAGE_TOTAL);
        Map<String, Long> before = counts();

        String conversationId = data(send(MEMBER_A, null, "어두운 방에서 키울 식물 있어?"))
                .get("conversationId").asString();

        for (String stage : everyTurn) {
            assertThat(count(stage)).as(stage).isEqualTo(before.get(stage) + 1);
        }
        assertThat(count(ChatbotService.STAGE_HISTORY)).isEqualTo(before.get(ChatbotService.STAGE_HISTORY));

        assertThat(send(MEMBER_A, conversationId, "물은 얼마나 줘?").statusCode()).isEqualTo(200);

        assertThat(count(ChatbotService.STAGE_HISTORY)).isEqualTo(before.get(ChatbotService.STAGE_HISTORY) + 1);
        assertThat(count(ChatbotService.STAGE_TOTAL)).isEqualTo(before.get(ChatbotService.STAGE_TOTAL) + 2);
    }

    @Test
    @DisplayName("[#634] 임베딩 실패(503)는 임베딩 · 전체가 실패로, LLM 실패는 답변 생성만 실패로 남고 전체는 성공(고정 문장 응답)")
    void recordsStageTimersOnFailure() throws Exception {
        Map<String, Long> before = counts();
        long embeddingFailures = failures(ChatbotService.STAGE_EMBEDDING);
        long totalFailures = failures(ChatbotService.STAGE_TOTAL);
        long answerFailures = failures(ChatbotService.STAGE_ANSWER);
        embeddingModel.failing.set(true);

        assertThat(send(MEMBER_A, null, "어두운 방").statusCode()).isEqualTo(503);

        assertThat(failures(ChatbotService.STAGE_EMBEDDING)).isEqualTo(embeddingFailures + 1);
        assertThat(failures(ChatbotService.STAGE_TOTAL)).isEqualTo(totalFailures + 1);
        assertThat(count(ChatbotService.STAGE_TOTAL)).isEqualTo(before.get(ChatbotService.STAGE_TOTAL));

        embeddingModel.failing.set(false);
        answerGenerator.failing.set(true);
        assertThat(send(MEMBER_A, null, "어두운 방").statusCode()).isEqualTo(200);

        assertThat(failures(ChatbotService.STAGE_ANSWER)).isEqualTo(answerFailures + 1);
        assertThat(count(ChatbotService.STAGE_ANSWER)).isEqualTo(before.get(ChatbotService.STAGE_ANSWER));
        assertThat(count(ChatbotService.STAGE_TOTAL)).isEqualTo(before.get(ChatbotService.STAGE_TOTAL) + 1);
    }

    private Map<String, Long> counts() {
        Map<String, Long> counts = new HashMap<>();
        for (String stage : List.of(ChatbotService.STAGE_HISTORY, ChatbotService.STAGE_EMBEDDING,
                ChatbotService.STAGE_PRODUCT_SEARCH, ChatbotService.STAGE_SPECIES_SEARCH, ChatbotService.STAGE_SEARCH,
                ChatbotService.STAGE_ANSWER,
                ChatbotService.STAGE_SAVE, ChatbotService.STAGE_TOTAL)) {
            counts.put(stage, count(stage));
        }
        return counts;
    }

    private long count(String stage) {
        return meterRegistry.get(ChatbotService.METRIC).tag("stage", stage)
                .tag("outcome", StageTimers.SUCCESS).timer().count();
    }

    // 실패 Timer는 처음 실패할 때 생기므로 없으면 0건
    private long failures(String stage) {
        Timer timer = meterRegistry.find(ChatbotService.METRIC).tag("stage", stage)
                .tag("outcome", StageTimers.FAILURE).timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    @DisplayName("같은 conversationId로 이어 물으면 이전 질문 · 답변이 프롬프트에 들어가고, 검색은 직전 질문까지 붙여 한다")
    void continuesConversation() throws Exception {
        String conversationId = data(send(MEMBER_A, null, "어두운 방에서 키울 식물 있어?"))
                .get("conversationId").asString();

        HttpResponse<String> second = send(MEMBER_A, conversationId, "물은 얼마나 줘?");

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(data(second).get("conversationId").asString()).isEqualTo(conversationId);
        // 「물은 얼마나 줘?」만으로는 「어두운」이 없어 그늘 상품을 못 찾지만, 직전 질문을 붙여 찾는다
        assertThat(productIds(data(second))).containsExactly(1L);
        ChatPrompt prompt = answerGenerator.prompts.get(1);
        assertThat(prompt.history()).containsExactly(
                new ChatTurn(ChatTurn.Role.USER, "어두운 방에서 키울 식물 있어?"),
                new ChatTurn(ChatTurn.Role.ASSISTANT, "그늘 몬스테라를 추천해요."));
    }

    @Test
    @DisplayName("맞는 상품이 없는 상황 질문은 가장 가까운 판매 중 상품을 대안으로 넣고, 답변에 나온 대안만 카드로 준다")
    void addsAlternativesWhenNoProductMatches() throws Exception {
        // 「개업 선물」은 스텁 축에 없는 낱말이라 모든 상품과 유사도가 0.28 미만 → 맞는 상품 0개
        HttpResponse<String> response = send(MEMBER_A, null, "개업 선물로 뭐가 좋아?");

        assertThat(response.statusCode()).isEqualTo(200);
        // 가짜 LLM 답변 「그늘 몬스테라를 추천해요.」에 나온 대안만 카드로
        assertThat(productIds(data(response))).containsExactly(1L);
        assertThat(answerGenerator.prompts.getFirst().userMessage())
                .contains("질문과 비슷한 판매 중 상품이 없습니다.",
                        "<alternative title=\"그늘 몬스테라\"", "<alternative title=\"선인장 모음\"")
                .doesNotContain("그늘 스킨답서스", "그늘 고사리");
    }

    @Test
    @DisplayName("맞는 상품이 없는데 LLM이 실패하면 대안 카드 없이 고정 문장만 준다")
    void llmFailureWithoutProductsHasNoAlternativeCards() throws Exception {
        answerGenerator.failing.set(true);

        JsonNode data = data(send(MEMBER_A, null, "개업 선물로 뭐가 좋아?"));

        assertThat(data.get("answer").asString()).isEqualTo("지금은 답변을 만들지 못했어요. 잠시 후 다시 물어봐 주세요.");
        assertThat(productIds(data)).isEmpty();
    }

    @Test
    @DisplayName("다른 회원의 대화는 403, 없는 대화는 404이고 LLM을 부르지 않는다")
    void rejectsOthersConversation() throws Exception {
        String conversationId = data(send(MEMBER_A, null, "어두운 방 식물")).get("conversationId").asString();
        answerGenerator.prompts.clear();

        HttpResponse<String> other = send(MEMBER_B, conversationId, "나도 물어볼래");
        HttpResponse<String> missing = send(MEMBER_A, "00000000-0000-0000-0000-000000000000", "없는 대화");

        assertThat(other.statusCode()).isEqualTo(403);
        assertThat(json.readTree(other.body()).get("code").asString()).isEqualTo("CONVERSATION_FORBIDDEN");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json.readTree(missing.body()).get("code").asString()).isEqualTo("CONVERSATION_NOT_FOUND");
        assertThat(answerGenerator.prompts).isEmpty();
    }

    @Test
    @DisplayName("LLM이 실패해도 200으로 고정 문장과 찾은 상품 카드를 준다")
    void llmFailureStillReturnsProducts() throws Exception {
        answerGenerator.failing.set(true);

        HttpResponse<String> response = send(MEMBER_A, null, "어두운 방에서 키울 식물");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = data(response);
        assertThat(data.get("answerGenerated").asBoolean()).isFalse();
        assertThat(data.get("answer").asString()).contains("답변을 만들지 못했어요");
        assertThat(productIds(data)).containsExactly(1L);
    }

    @Test
    @DisplayName("질문 임베딩이 실패하면 503 CHATBOT_UNAVAILABLE이고 대화를 남기지 않는다")
    void embeddingFailureIs503() throws Exception {
        embeddingModel.failing.set(true);

        HttpResponse<String> response = send(MEMBER_A, null, "어두운 방");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("CHATBOT_UNAVAILABLE");
        assertThat(jdbcClient.sql("SELECT count(*) FROM chat_conversation").query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("품종 문서는 품종 의미 검색의 최소 유사도를 넘는 것만 근거로 넣고, 없으면 「맞는 품종 정보 없음」을 넣는다")
    void speciesGroundingUsesSpeciesSearchThreshold() throws Exception {
        // 산세베리아(93)는 「그늘」 축이라 「어두운」 질문과 가깝고, 몬스테라(62)는 「건조」 축이라 최소 유사도 0.35 미만
        speciesVector(93, "산세베리아 그늘에서도 잘 자람");
        speciesVector(62, "몬스테라 건조하게");

        JsonNode matched = data(send(MEMBER_A, null, "어두운 방에서 키울 식물 있어?"));
        JsonNode unmatched = data(send(MEMBER_A, null, "로즈마리 키우는 법"));

        assertThat(matched.get("species").valueStream().map(s -> s.get("speciesId").asInt()).toList())
                .containsExactly(93);
        assertThat(answerGenerator.prompts.get(0).userMessage())
                .contains("<species name=\"산세베리아\">", "산세베리아 그늘에서도 잘 자람")
                .doesNotContain("몬스테라 건조하게");
        assertThat(unmatched.get("species")).isEmpty();
        assertThat(answerGenerator.prompts.get(1).userMessage()).contains("질문과 맞는 품종 정보가 없습니다.");
    }

    @Test
    @DisplayName("빈 질문 · 300자 넘는 질문은 400, 토큰이 없으면 401")
    void validatesInput() throws Exception {
        assertThat(send(MEMBER_A, null, " ").statusCode()).isEqualTo(400);
        assertThat(send(MEMBER_A, null, "가".repeat(301)).statusCode()).isEqualTo(400);
        assertThat(send(MEMBER_A, null, "가".repeat(300)).statusCode()).isEqualTo(200);
        assertThat(send(null, null, "어두운 방").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> send(String token, String conversationId, String message) throws Exception {
        String body = json.writeValueAsString(conversationId == null
                ? java.util.Map.of("message", message)
                : java.util.Map.of("conversationId", conversationId, "message", message));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/chatbot/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void speciesVector(int speciesId, String content) {
        jdbcClient.sql("INSERT INTO species_vector_store (id, content, metadata, embedding) "
                        + "VALUES (:id, :content, '{}'::json, CAST(:v AS vector))")
                .param("id", speciesId)
                .param("content", content)
                .param("v", java.util.Arrays.toString(embeddingModel.vectorOf(content)).replace(" ", ""))
                .update();
    }

    private JsonNode data(HttpResponse<String> response) {
        return json.readTree(response.body()).get("data");
    }

    private static List<Long> productIds(JsonNode data) {
        return data.get("products").valueStream().map(p -> p.get("productId").asLong()).toList();
    }
}

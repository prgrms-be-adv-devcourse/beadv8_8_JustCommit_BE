package com.justcommit.backend.ai.chatbot.application;

import com.justcommit.backend.ai.chatbot.application.ChatSearchResult.ProductHit;
import com.justcommit.backend.ai.chatbot.application.ChatSearchResult.SpeciesHit;
import com.justcommit.backend.ai.chatbot.infrastructure.ChatSearchRepository;
import com.justcommit.backend.ai.chatbot.infrastructure.ConversationRepository;
import com.justcommit.backend.ai.common.StageTimers;
import com.justcommit.backend.ai.search.application.QueryEmbedder;
import com.justcommit.backend.ai.search.infrastructure.SpeciesSearchQueryRepository;
import com.justcommit.backend.ai.search.infrastructure.SpeciesSearchQueryRepository.SimilarSpecies;
import com.justcommit.backend.common.exception.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 챗봇 메시지 (#300 · #302): 대화 주인 확인 → 질문 임베딩(이어 묻기는 직전 질문 + 이번 질문) → 상품 · 품종 의미 검색
 * (맞는 상품이 없으면 가장 가까운 판매 중 상품을 대안으로) → LLM 답변 → 대화 기록 저장.
 * 질문 임베딩과 품종 찾기는 품종 의미 검색(#570)의 QueryEmbedder(3초 · 재시도 없음) · SpeciesSearchQueryRepository · 최소 유사도를 같이 쓴다.
 * 외부 호출(임베딩 · LLM)은 트랜잭션 밖에서 하고, 저장만 짧은 트랜잭션으로 묶는다.
 * - 임베딩 실패: 검색을 못 하므로 503 CHATBOT_UNAVAILABLE (대화 기록도 남기지 않음)
 * - LLM 실패 · 키 없음: 고정 문장 + 찾은 상품 카드 (answerGenerated=false)
 *
 * 단계별 시간(#634)은 {@code ai.chatbot.stage}: 대화 기록 읽기(이어 묻기만) · 질문 임베딩 · 상품 검색(대안 포함) · 품종 검색 ·
 * 두 검색을 동시에 한 전체(#636) · 답변 생성(키가 있을 때만) · 저장 · 전체를 {@code outcome}(success · failure)으로 나눠 남긴다. 답변 생성 실패는 고정 문장으로
 * 응답하므로 전체는 성공, 404 · 403 · 503은 전체도 실패. 구간 범위 5ms ~ 30초 (Gateway 응답 제한 20초를 덮음).
 *
 * 상품 검색과 품종 검색은 서로 결과를 쓰지 않으므로 가상 스레드 두 개에서 동시에 한다 (#636). 두 검색이 모두 끝난 뒤
 * 하나라도 실패했으면 상품 검색 → 품종 검색 순서로 첫 실패를 그대로 던진다 (차례로 하던 때와 같은 응답). 단계 시간은 검색마다 따로 남는다.
 */
@Slf4j
@Service
public class ChatbotService {

    static final String FALLBACK_WITH_PRODUCTS = "지금은 답변을 만들지 못했어요. 질문과 비슷한 판매 중 식물을 찾아 두었으니 아래 상품을 확인해 주세요.";
    static final String FALLBACK_WITHOUT_PRODUCTS = "지금은 답변을 만들지 못했어요. 잠시 후 다시 물어봐 주세요.";

    public static final String METRIC = "ai.chatbot.stage";
    public static final String STAGE_HISTORY = "history";
    public static final String STAGE_EMBEDDING = "embedding";
    public static final String STAGE_PRODUCT_SEARCH = "product_search";
    public static final String STAGE_SPECIES_SEARCH = "species_search";
    // 상품 · 품종 검색을 동시에 한 전체 (두 검색 중 긴 쪽 + 스레드 전환, #636). 다른 단계와 겹치므로 단계 합에 넣지 않는다
    public static final String STAGE_SEARCH = "search";
    public static final String STAGE_ANSWER = "answer";
    public static final String STAGE_SAVE = "save";
    public static final String STAGE_TOTAL = "total";

    private final ConversationRepository conversationRepository;
    private final ChatSearchRepository searchRepository;
    private final SpeciesSearchQueryRepository speciesQueryRepository;
    private final QueryEmbedder queryEmbedder;
    private final AnswerGenerator answerGenerator;
    private final ChatPromptFactory promptFactory;
    private final TransactionTemplate transactionTemplate;
    private final int productTopK;
    private final int alternativeTopK;
    private final int speciesTopK;
    private final double productMinSimilarity;
    private final double speciesMinSimilarity;
    private final int historySize;
    private final StageTimers timers;
    // 상품 · 품종 검색 동시 실행용 (요청마다 가상 스레드 2개, DB 연결 풀이 실제 동시 수를 막음). 로그 traceId를 넘겨준다
    private final SimpleAsyncTaskExecutor searchExecutor;

    public ChatbotService(
            ConversationRepository conversationRepository,
            ChatSearchRepository searchRepository,
            SpeciesSearchQueryRepository speciesQueryRepository,
            QueryEmbedder queryEmbedder,
            AnswerGenerator answerGenerator,
            ChatPromptFactory promptFactory,
            TransactionTemplate transactionTemplate,
            @Value("${custom.chatbot.product-top-k:6}") int productTopK,
            @Value("${custom.chatbot.alternative-top-k:3}") int alternativeTopK,
            @Value("${custom.chatbot.species-top-k:3}") int speciesTopK,
            @Value("${custom.chatbot.product-min-similarity:0.28}") double productMinSimilarity,
            @Value("${custom.species-search.min-similarity:0.35}") double speciesMinSimilarity,
            @Value("${custom.chatbot.history-size:10}") int historySize,
            MeterRegistry meterRegistry) {
        this.conversationRepository = conversationRepository;
        this.searchRepository = searchRepository;
        this.speciesQueryRepository = speciesQueryRepository;
        this.queryEmbedder = queryEmbedder;
        this.answerGenerator = answerGenerator;
        this.promptFactory = promptFactory;
        this.transactionTemplate = transactionTemplate;
        this.productTopK = productTopK;
        this.alternativeTopK = alternativeTopK;
        this.speciesTopK = speciesTopK;
        this.productMinSimilarity = productMinSimilarity;
        this.speciesMinSimilarity = speciesMinSimilarity;
        this.historySize = historySize;
        this.timers = new StageTimers(meterRegistry, METRIC, "챗봇 메시지 단계별 시간 (#634)",
                List.of(STAGE_HISTORY, STAGE_EMBEDDING, STAGE_PRODUCT_SEARCH, STAGE_SPECIES_SEARCH, STAGE_SEARCH,
                        STAGE_ANSWER, STAGE_SAVE, STAGE_TOTAL),
                Duration.ofMillis(5), Duration.ofSeconds(30));
        this.searchExecutor = new SimpleAsyncTaskExecutor("chatbot-search-");
        this.searchExecutor.setVirtualThreads(true);
        this.searchExecutor.setTaskDecorator(new ContextPropagatingTaskDecorator());
    }

    private record ProductSearch(List<ProductHit> products, List<ProductHit> alternatives) {
    }

    // cards: 화면 상품 카드. 맞는 상품이 있으면 그 상품, 없으면 답변에 제목이 나온 대안 상품만
    public record ChatReply(UUID conversationId, String answer, boolean answerGenerated, ChatSearchResult result,
            List<ProductHit> cards) {
    }

    public ChatReply send(long memberId, UUID conversationId, String message) {
        long startedAt = System.nanoTime();
        return timers.time(STAGE_TOTAL, () -> send(memberId, conversationId, message, startedAt));
    }

    private ChatReply send(long memberId, UUID conversationId, String message, long startedAt) {
        String question = message.strip();

        boolean isNew = conversationId == null;
        UUID id = isNew ? UUID.randomUUID() : conversationId;
        List<ChatTurn> history = List.of();
        if (!isNew) {
            history = timers.time(STAGE_HISTORY, () -> loadHistory(memberId, id));
        }

        String searchText = searchText(history, question);
        float[] queryVector = timers.time(STAGE_EMBEDDING, () -> embed(searchText));

        long searchStartedAt = System.nanoTime();
        // 대안 상품 검색(맞는 상품이 0개일 때만 한 번 더 부르는 DB 쿼리)도 상품 검색 단계에 넣는다
        CompletableFuture<ProductSearch> productTask = async(() -> timers.time(STAGE_PRODUCT_SEARCH, () -> {
            List<ProductHit> hits = searchRepository.findProducts(queryVector, productTopK, productMinSimilarity);
            return new ProductSearch(hits, alternatives(queryVector, hits));
        }));
        CompletableFuture<List<SpeciesHit>> speciesTask =
                async(() -> timers.time(STAGE_SPECIES_SEARCH, () -> findSpecies(queryVector)));
        // 둘 다 끝날 때까지 기다린 뒤(실패해도 다른 쪽 DB 쿼리를 응답 뒤에 남기지 않음) 상품 → 품종 순서로 결과 · 실패를 꺼낸다
        CompletableFuture.allOf(productTask, speciesTask).exceptionally(e -> null).join();
        if (productTask.isCompletedExceptionally() || speciesTask.isCompletedExceptionally()) {
            timers.recordFailure(STAGE_SEARCH, searchStartedAt);
        } else {
            timers.record(STAGE_SEARCH, searchStartedAt);
        }
        ProductSearch found = result(productTask);
        List<SpeciesHit> species = result(speciesTask);
        ChatSearchResult result = new ChatSearchResult(found.products(), species, found.alternatives());

        String answer = null;
        int llmCalls = 0;
        if (answerGenerator.isConfigured()) {
            llmCalls = 1;
            List<ChatTurn> turns = history;
            try {
                answer = timers.time(STAGE_ANSWER,
                        () -> plainText(answerGenerator.answer(promptFactory.create(question, turns, result))));
            } catch (RuntimeException e) {
                // 실패(시간 초과 등)도 outcome=failure로 남는다: 고정 문장으로 답하기 전에 사용자가 기다린 시간
                log.warn("챗봇 답변 생성 실패, 고정 문장으로 답함: conversationId={}, error={}", id, e.toString());
            }
        }
        boolean generated = answer != null;
        if (!generated) {
            answer = result.products().isEmpty() ? FALLBACK_WITHOUT_PRODUCTS : FALLBACK_WITH_PRODUCTS;
        }

        String savedAnswer = answer;
        timers.time(STAGE_SAVE, () -> transactionTemplate.execute(status -> {
            if (isNew) {
                conversationRepository.create(id, memberId);
            }
            conversationRepository.appendExchange(id, question, savedAnswer);
            return null;
        }));

        log.info("챗봇 메시지: conversationId={}, memberId={}, elapsedMs={}, embeddingCalls=1, llmCalls={}, "
                        + "answerGenerated={}, products={}, alternatives={}, species={}",
                id, memberId, (System.nanoTime() - startedAt) / 1_000_000, llmCalls, generated,
                result.products().size(), result.alternatives().size(), result.species().size());
        return new ChatReply(id, answer, generated, result, cards(result, generated ? answer : null));
    }

    private <T> CompletableFuture<T> async(Supplier<T> step) {
        return CompletableFuture.supplyAsync(step, searchExecutor);
    }

    // 끝난 검색의 결과. 실패면 검색 안에서 난 예외를 감싸지 않고 그대로 던진다
    private static <T> T result(CompletableFuture<T> task) {
        try {
            return task.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    /**
     * 검색에 쓸 문장. 이어 묻는 질문(「그중에 물 덜 줘도 되는 건?」)은 그 문장만으로는 무엇을 가리키는지 없어서
     * 직전 질문을 앞에 붙인다. LLM으로 질문을 다시 쓰지 않으므로 임베딩 1번 그대로다 (#297 「질의 압축」 제외와 별개)
     */
    static String searchText(List<ChatTurn> history, String question) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatTurn turn = history.get(i);
            if (turn.role() == ChatTurn.Role.USER) {
                return turn.content() + " " + question;
            }
        }
        return question;
    }

    /**
     * 질문과 맞는 판매 중 상품(유사도 하한 이상)이 0개일 때만, 하한 없이 가장 가까운 판매 중 상품을 대안으로 찾는다.
     * 「개업 선물」처럼 상황을 묻는 질문은 상품 문서와 유사도가 낮아 0개가 되기 쉬운데, 그때도 LLM이 대신 권할 상품이 있게 한다.
     * 외부 호출 없이 DB 쿼리만 한 번 더 한다
     */
    private List<ProductHit> alternatives(float[] queryVector, List<ProductHit> products) {
        if (!products.isEmpty()) {
            return List.of();
        }
        return searchRepository.findProducts(queryVector, alternativeTopK, -1.0);
    }

    /**
     * 화면 카드. 대안 상품은 날씨처럼 관계없는 질문에도 찾아지므로 모두 붙이지 않고, LLM 답변에 제목이 나온 것만 붙인다.
     * 답변을 만들지 못했으면(answer null) 대안 카드는 없다
     */
    static List<ProductHit> cards(ChatSearchResult result, String answer) {
        if (!result.products().isEmpty()) {
            return result.products();
        }
        if (answer == null) {
            return List.of();
        }
        return result.alternatives().stream()
                .filter(p -> p.title() != null && !p.title().isBlank() && answer.contains(p.title()))
                .toList();
    }

    /**
     * 화면은 답변을 글자 그대로 보여 주므로 마크다운 강조(**굵게**, __굵게__)를 지운다.
     * 프롬프트에 「마크다운 금지」가 있어도 가끔 섞여 나온다 (#303 품질 점검)
     */
    static String plainText(String answer) {
        return answer.replace("**", "").replace("__", "").strip();
    }

    // 대화 주인 확인: 없는 대화 404, 다른 회원 403
    private List<ChatTurn> loadHistory(long memberId, UUID conversationId) {
        long owner = conversationRepository.findOwner(conversationId)
                .orElseThrow(() -> new BusinessException(ChatbotErrorCode.CONVERSATION_NOT_FOUND));
        if (owner != memberId) {
            throw new BusinessException(ChatbotErrorCode.CONVERSATION_FORBIDDEN);
        }
        return conversationRepository.findRecentTurns(conversationId, historySize);
    }

    /**
     * 품종 의미 검색과 같은 쿼리 · 최소 유사도로 품종을 고르고 문서 본문을 붙인다 (유사도 순 유지).
     * 하나도 없으면 빈 목록 → 프롬프트에 「맞는 품종 정보 없음」이 들어가 LLM이 「목록에 없다」고 답한다
     */
    private List<SpeciesHit> findSpecies(float[] queryVector) {
        List<SimilarSpecies> similar = speciesQueryRepository.findSimilarSpecies(
                queryVector, speciesMinSimilarity, speciesTopK);
        Map<Integer, String> documents = searchRepository.findSpeciesDocuments(
                similar.stream().map(SimilarSpecies::speciesId).toList());
        return similar.stream()
                .map(s -> new SpeciesHit(s.speciesId(), s.name(), documents.getOrDefault(s.speciesId(), ""),
                        s.similarity()))
                .toList();
    }

    private float[] embed(String question) {
        try {
            return queryEmbedder.embed(question);
        } catch (RuntimeException e) {
            log.warn("챗봇 질문 임베딩 실패: error={}", e.toString());
            throw new BusinessException(ChatbotErrorCode.CHATBOT_UNAVAILABLE);
        }
    }
}

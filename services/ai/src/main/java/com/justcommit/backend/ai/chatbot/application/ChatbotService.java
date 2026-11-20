package com.justcommit.backend.ai.chatbot.application;

import com.justcommit.backend.ai.chatbot.application.ChatSearchResult.SpeciesHit;
import com.justcommit.backend.ai.chatbot.infrastructure.ChatSearchRepository;
import com.justcommit.backend.ai.chatbot.infrastructure.ConversationRepository;
import com.justcommit.backend.ai.search.application.QueryEmbedder;
import com.justcommit.backend.ai.search.infrastructure.SpeciesSearchQueryRepository;
import com.justcommit.backend.ai.search.infrastructure.SpeciesSearchQueryRepository.SimilarSpecies;
import com.justcommit.backend.common.exception.BusinessException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 챗봇 메시지 (#300 · #302): 대화 주인 확인 → 질문 임베딩(이어 묻기는 직전 질문 + 이번 질문) → 상품 · 품종 의미 검색 → LLM 답변 → 대화 기록 저장.
 * 질문 임베딩과 품종 찾기는 품종 의미 검색(#570)의 QueryEmbedder(3초 · 재시도 없음) · SpeciesSearchQueryRepository · 최소 유사도를 같이 쓴다.
 * 외부 호출(임베딩 · LLM)은 트랜잭션 밖에서 하고, 저장만 짧은 트랜잭션으로 묶는다.
 * - 임베딩 실패: 검색을 못 하므로 503 CHATBOT_UNAVAILABLE (대화 기록도 남기지 않음)
 * - LLM 실패 · 키 없음: 고정 문장 + 찾은 상품 카드 (answerGenerated=false)
 */
@Slf4j
@Service
public class ChatbotService {

    static final String FALLBACK_WITH_PRODUCTS = "지금은 답변을 만들지 못했어요. 질문과 비슷한 판매 중 식물을 찾아 두었으니 아래 상품을 확인해 주세요.";
    static final String FALLBACK_WITHOUT_PRODUCTS = "지금은 답변을 만들지 못했어요. 잠시 후 다시 물어봐 주세요.";

    private final ConversationRepository conversationRepository;
    private final ChatSearchRepository searchRepository;
    private final SpeciesSearchQueryRepository speciesQueryRepository;
    private final QueryEmbedder queryEmbedder;
    private final AnswerGenerator answerGenerator;
    private final ChatPromptFactory promptFactory;
    private final TransactionTemplate transactionTemplate;
    private final int productTopK;
    private final int speciesTopK;
    private final double productMinSimilarity;
    private final double speciesMinSimilarity;
    private final int historySize;

    public ChatbotService(
            ConversationRepository conversationRepository,
            ChatSearchRepository searchRepository,
            SpeciesSearchQueryRepository speciesQueryRepository,
            QueryEmbedder queryEmbedder,
            AnswerGenerator answerGenerator,
            ChatPromptFactory promptFactory,
            TransactionTemplate transactionTemplate,
            @Value("${custom.chatbot.product-top-k:6}") int productTopK,
            @Value("${custom.chatbot.species-top-k:3}") int speciesTopK,
            @Value("${custom.chatbot.product-min-similarity:0.28}") double productMinSimilarity,
            @Value("${custom.species-search.min-similarity:0.35}") double speciesMinSimilarity,
            @Value("${custom.chatbot.history-size:10}") int historySize) {
        this.conversationRepository = conversationRepository;
        this.searchRepository = searchRepository;
        this.speciesQueryRepository = speciesQueryRepository;
        this.queryEmbedder = queryEmbedder;
        this.answerGenerator = answerGenerator;
        this.promptFactory = promptFactory;
        this.transactionTemplate = transactionTemplate;
        this.productTopK = productTopK;
        this.speciesTopK = speciesTopK;
        this.productMinSimilarity = productMinSimilarity;
        this.speciesMinSimilarity = speciesMinSimilarity;
        this.historySize = historySize;
    }

    public record ChatReply(UUID conversationId, String answer, boolean answerGenerated, ChatSearchResult result) {
    }

    public ChatReply send(long memberId, UUID conversationId, String message) {
        long startedAt = System.nanoTime();
        String question = message.strip();

        boolean isNew = conversationId == null;
        UUID id = isNew ? UUID.randomUUID() : conversationId;
        List<ChatTurn> history = isNew ? List.of() : loadHistory(memberId, id);

        float[] queryVector = embed(searchText(history, question));
        ChatSearchResult result = new ChatSearchResult(
                searchRepository.findProducts(queryVector, productTopK, productMinSimilarity),
                findSpecies(queryVector));

        String answer = null;
        int llmCalls = 0;
        if (answerGenerator.isConfigured()) {
            llmCalls = 1;
            try {
                answer = plainText(answerGenerator.answer(promptFactory.create(question, history, result)));
            } catch (RuntimeException e) {
                log.warn("챗봇 답변 생성 실패, 고정 문장으로 답함: conversationId={}, error={}", id, e.toString());
            }
        }
        boolean generated = answer != null;
        if (!generated) {
            answer = result.products().isEmpty() ? FALLBACK_WITHOUT_PRODUCTS : FALLBACK_WITH_PRODUCTS;
        }

        String savedAnswer = answer;
        transactionTemplate.executeWithoutResult(status -> {
            if (isNew) {
                conversationRepository.create(id, memberId);
            }
            conversationRepository.appendExchange(id, question, savedAnswer);
        });

        log.info("챗봇 메시지: conversationId={}, memberId={}, elapsedMs={}, embeddingCalls=1, llmCalls={}, "
                        + "answerGenerated={}, products={}, species={}",
                id, memberId, (System.nanoTime() - startedAt) / 1_000_000, llmCalls, generated,
                result.products().size(), result.species().size());
        return new ChatReply(id, answer, generated, result);
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

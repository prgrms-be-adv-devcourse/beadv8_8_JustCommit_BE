package com.justcommit.backend.ai.chatbot.presentation;

import com.justcommit.backend.ai.chatbot.application.ChatSearchResult;
import com.justcommit.backend.ai.chatbot.application.ChatbotService.ChatReply;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * answerGenerated가 false면 answer는 고정 문장(LLM 실패 · 키 없음)이고 상품 카드는 그대로 온다.
 * products는 판매 중 상품만 (유사도 높은 순), species는 답변 근거로 쓴 품종
 */
public record ChatbotMessageResponse(
        UUID conversationId,
        String answer,
        boolean answerGenerated,
        List<ProductCard> products,
        List<SpeciesRef> species) {

    public record ProductCard(long productId, String title, BigDecimal price, String thumbnailUrl, long speciesId,
            String speciesName) {
    }

    public record SpeciesRef(int speciesId, String name) {
    }

    public static ChatbotMessageResponse from(ChatReply reply) {
        ChatSearchResult result = reply.result();
        return new ChatbotMessageResponse(
                reply.conversationId(),
                reply.answer(),
                reply.answerGenerated(),
                result.products().stream()
                        .map(p -> new ProductCard(p.productId(), p.title(), p.price(), p.thumbnailUrl(),
                                p.speciesId(), p.speciesName()))
                        .toList(),
                result.species().stream().map(s -> new SpeciesRef(s.speciesId(), s.name())).toList());
    }
}

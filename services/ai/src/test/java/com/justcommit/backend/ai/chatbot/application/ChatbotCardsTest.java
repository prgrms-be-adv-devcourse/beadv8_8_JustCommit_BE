package com.justcommit.backend.ai.chatbot.application;

import static com.justcommit.backend.ai.chatbot.application.ChatPromptFactoryTest.hit;
import static org.assertj.core.api.Assertions.assertThat;

import com.justcommit.backend.ai.chatbot.application.ChatSearchResult.ProductHit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatbotCardsTest {

    private final ProductHit sansevieria = hit(1L, "산세베리아 중품");
    private final ProductHit stucky = hit(2L, "스투키 소품");

    @Test
    @DisplayName("맞는 상품이 있으면 그 상품이 모두 카드가 된다")
    void productsBecomeCards() {
        ChatSearchResult result = new ChatSearchResult(List.of(sansevieria, stucky), List.of(), List.of());

        assertThat(ChatbotService.cards(result, "아무 답변")).containsExactly(sansevieria, stucky);
        assertThat(ChatbotService.cards(result, null)).containsExactly(sansevieria, stucky);
    }

    @Test
    @DisplayName("대안 상품은 답변에 제목이 나온 것만 카드가 된다")
    void onlyMentionedAlternativesBecomeCards() {
        ChatSearchResult result = new ChatSearchResult(List.of(), List.of(), List.of(sansevieria, stucky));

        assertThat(ChatbotService.cards(result,
                "개업 선물로는 보통 금전수를 많이 해요. 대신 판매 중인 스투키 소품을 추천해 드려요."))
                .containsExactly(stucky);
    }

    @Test
    @DisplayName("관계없는 질문 답변이나 답변 생성 실패면 대안 카드가 없다")
    void noAlternativeCardsWhenNotMentioned() {
        ChatSearchResult result = new ChatSearchResult(List.of(), List.of(), List.of(sansevieria, stucky));

        assertThat(ChatbotService.cards(result, "죄송하지만 식물 질문만 도와드릴 수 있어요.")).isEmpty();
        assertThat(ChatbotService.cards(result, null)).isEmpty();
    }
}

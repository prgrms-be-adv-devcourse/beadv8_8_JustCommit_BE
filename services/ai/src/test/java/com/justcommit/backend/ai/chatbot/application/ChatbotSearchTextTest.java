package com.justcommit.backend.ai.chatbot.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.justcommit.backend.ai.chatbot.application.ChatTurn.Role;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 검색 문장: 새 대화는 질문 그대로, 이어 묻기는 직전 질문 + 이번 질문
class ChatbotSearchTextTest {

    @Test
    @DisplayName("새 대화는 이번 질문만으로 검색한다")
    void newConversation() {
        assertThat(ChatbotService.searchText(List.of(), "햇빛 적은 방 식물")).isEqualTo("햇빛 적은 방 식물");
    }

    @Test
    @DisplayName("이어 묻기는 가장 최근 사용자 질문을 앞에 붙인다 (답변 · 더 옛날 질문은 넣지 않음)")
    void followUp() {
        List<ChatTurn> history = List.of(
                new ChatTurn(Role.USER, "개업 선물"),
                new ChatTurn(Role.ASSISTANT, "금전수를 추천해요."),
                new ChatTurn(Role.USER, "햇빛 적은 방에서 키우기 쉬운 식물 있어?"),
                new ChatTurn(Role.ASSISTANT, "스킨답서스를 추천해요."));

        assertThat(ChatbotService.searchText(history, "그중에 물 덜 줘도 되는 건?"))
                .isEqualTo("햇빛 적은 방에서 키우기 쉬운 식물 있어? 그중에 물 덜 줘도 되는 건?");
    }
}

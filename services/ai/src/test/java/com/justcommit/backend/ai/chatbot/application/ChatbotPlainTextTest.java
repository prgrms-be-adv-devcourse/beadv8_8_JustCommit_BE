package com.justcommit.backend.ai.chatbot.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatbotPlainTextTest {

    @Test
    @DisplayName("답변의 마크다운 강조(** · __)를 지우고 나머지 글은 그대로 둔다")
    void removesMarkdownEmphasis() {
        assertThat(ChatbotService.plainText("**산세베리아 슈퍼바 중품**이 __가장__ 적합해요. 물은 2~3주에 한 번 "))
                .isEqualTo("산세베리아 슈퍼바 중품이 가장 적합해요. 물은 2~3주에 한 번");
    }
}

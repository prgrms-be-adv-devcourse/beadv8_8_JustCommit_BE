package com.justcommit.backend.ai.chatbot.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

// conversationId가 없으면 새 대화를 발급한다
public record ChatbotMessageRequest(
        UUID conversationId,
        @NotBlank @Size(max = 300) String message) {
}

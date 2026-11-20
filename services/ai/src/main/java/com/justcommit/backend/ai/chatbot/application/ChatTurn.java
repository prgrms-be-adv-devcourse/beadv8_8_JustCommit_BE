package com.justcommit.backend.ai.chatbot.application;

// 대화 기록 한 줄 (chat_message). role은 USER · ASSISTANT
public record ChatTurn(Role role, String content) {

    public enum Role { USER, ASSISTANT }
}

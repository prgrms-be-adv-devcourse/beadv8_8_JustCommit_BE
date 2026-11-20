package com.justcommit.backend.ai.chatbot.application;

import java.util.List;

/**
 * LLM에 보낼 내용. history는 이전 대화(오래된 순, USER · ASSISTANT가 번갈아), userMessage는 이번 질문 + 검색 결과.
 * 검색 결과는 이번 질문에만 붙이고 대화 기록에는 질문 원문만 저장한다 (다음 턴에 옛 검색 결과가 쌓이지 않게)
 */
public record ChatPrompt(String system, List<ChatTurn> history, String userMessage) {
}

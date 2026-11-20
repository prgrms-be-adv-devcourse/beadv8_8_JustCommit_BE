package com.justcommit.backend.ai.chatbot.application;

// 답변 생성 LLM (운영: Claude Haiku, 테스트: 가짜 구현). 실패하면 아무 RuntimeException이나 던진다
public interface AnswerGenerator {

    /** 키가 없으면 false: 부르지 않고 고정 문장으로 답한다 (로컬에서 앱은 뜨고 검색은 됨) */
    boolean isConfigured();

    String answer(ChatPrompt prompt);
}

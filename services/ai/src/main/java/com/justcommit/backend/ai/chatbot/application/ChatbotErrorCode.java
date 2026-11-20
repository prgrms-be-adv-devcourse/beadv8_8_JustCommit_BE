package com.justcommit.backend.ai.chatbot.application;

import com.justcommit.backend.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ChatbotErrorCode implements ErrorCode {
    // 질문 임베딩 실패: 검색 자체를 못 함 (LLM 실패는 에러가 아니라 고정 문장 + 상품 카드)
    CHATBOT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "챗봇을 지금 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND, "대화를 찾을 수 없습니다."),
    CONVERSATION_FORBIDDEN(HttpStatus.FORBIDDEN, "다른 회원의 대화는 이어 쓸 수 없습니다.");

    private final HttpStatus status;
    private final String message;
}

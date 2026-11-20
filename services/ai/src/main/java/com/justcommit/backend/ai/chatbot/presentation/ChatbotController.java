package com.justcommit.backend.ai.chatbot.presentation;

import com.justcommit.backend.ai.chatbot.application.ChatbotService;
import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.common.security.AuthMember;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 챗봇 외부 API (로그인 필요). Gateway /api/v1/chatbot/** → ai, 응답 20초 · 회원당 요청 수 제한 (#297)
@RestController
@RequestMapping("/api/v1/chatbot")
@RequiredArgsConstructor
public class ChatbotController {

    private final ChatbotService chatbotService;

    @PostMapping("/messages")
    public ApiResponse<ChatbotMessageResponse> send(
            @AuthenticationPrincipal AuthMember member,
            @Valid @RequestBody ChatbotMessageRequest request) {
        return ApiResponse.ok("챗봇이 답했습니다.", ChatbotMessageResponse.from(
                chatbotService.send(member.memberId(), request.conversationId(), request.message())));
    }
}

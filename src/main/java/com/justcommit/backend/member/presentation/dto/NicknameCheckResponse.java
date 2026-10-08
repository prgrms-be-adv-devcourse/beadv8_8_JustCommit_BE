package com.justcommit.backend.member.presentation.dto;

// 닉네임 사용 가능 여부 응답
public record NicknameCheckResponse(
        boolean available
) {
}

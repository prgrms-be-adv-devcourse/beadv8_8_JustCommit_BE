package com.justcommit.backend.member.member.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import static com.justcommit.backend.member.presentation.validation.MemberValidation.NICKNAME_REGEX;

// 닉네임 변경 요청 (규칙은 가입과 같음: 한글 · 영문 · 숫자 2~8자)
public record NicknameChangeRequest(
    @NotBlank(message = "닉네임을 입력해 주세요.")
    @Pattern(regexp = NICKNAME_REGEX, message = "닉네임은 한글, 영문, 숫자 2~8자여야 합니다.")
    String nickname
) {
}

package com.justcommit.backend.member.member.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import static com.justcommit.backend.member.presentation.validation.MemberValidation.PASSWORD_REGEX;

// 비밀번호 변경 요청. 새 비밀번호 규칙은 가입과 같음 (8~20자, 영문 · 숫자 각 1개 이상)
public record PasswordChangeRequest(
    @NotBlank(message = "현재 비밀번호를 입력해 주세요.")
    String currentPassword,

    @NotBlank(message = "새 비밀번호를 입력해 주세요.")
    @Pattern(regexp = PASSWORD_REGEX, message = "비밀번호는 8~20자이며 영문과 숫자를 각각 1개 이상 포함해야 합니다.")
    String newPassword
) {
}

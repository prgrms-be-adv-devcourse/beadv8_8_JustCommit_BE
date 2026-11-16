package com.justcommit.backend.member.auth.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import static com.justcommit.backend.member.presentation.validation.MemberValidation.PASSWORD_REGEX;

// 비밀번호 재설정 요청 (#412). 재설정 목적 이메일 인증으로 받은 일회용 토큰 + 새 비밀번호 (규칙은 가입과 같음)
public record PasswordResetRequest(
    @NotBlank(message = "재설정 토큰이 필요합니다.")
    String resetToken,

    @NotBlank(message = "새 비밀번호를 입력해 주세요.")
    @Pattern(regexp = PASSWORD_REGEX, message = "비밀번호는 8~20자이며 영문과 숫자를 각각 1개 이상 포함해야 합니다.")
    String newPassword
) {
}

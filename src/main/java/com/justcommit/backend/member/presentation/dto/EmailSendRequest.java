package com.justcommit.backend.member.presentation.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

// 인증번호 발송 요청
public record EmailSendRequest(
        @NotBlank(message = "이메일을 입력해주세요.")
        @Email(regexp = MemberValidation.EMAIL_REGEX, message = "이메일 형식이 올바르지 않습니다.")
        String email
) {
}

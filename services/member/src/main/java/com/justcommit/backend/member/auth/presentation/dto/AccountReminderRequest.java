package com.justcommit.backend.member.auth.presentation.dto;

import com.justcommit.backend.member.presentation.validation.MemberValidation;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

// 가입 계정 안내 요청 (#412). 이메일 규칙은 인증번호 발송과 같음
public record AccountReminderRequest(
        @NotBlank(message = "이메일을 입력해주세요.")
        @Email(regexp = MemberValidation.EMAIL_REGEX, message = "이메일 형식이 올바르지 않습니다.")
        String email
) {
}

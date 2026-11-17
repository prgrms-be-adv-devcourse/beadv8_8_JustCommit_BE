package com.justcommit.backend.member.auth.presentation.dto;

import com.justcommit.backend.member.auth.domain.EmailPurpose;
import com.justcommit.backend.member.presentation.validation.MemberValidation;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

// 인증번호 발송 요청. purpose를 보내지 않으면 가입(SIGNUP) (#412, 기존 가입 요청은 그대로 동작)
public record EmailSendRequest(
        @NotBlank(message = "이메일을 입력해주세요.")
        @Email(regexp = MemberValidation.EMAIL_REGEX, message = "이메일 형식이 올바르지 않습니다.")
        String email,
        EmailPurpose purpose
) {
  // 목적 없이 만드는 기존 방식 (가입)
  public EmailSendRequest(String email) {
    this(email, null);
  }

  public EmailPurpose purposeOrDefault() {
    return purpose == null ? EmailPurpose.SIGNUP : purpose;
  }
}

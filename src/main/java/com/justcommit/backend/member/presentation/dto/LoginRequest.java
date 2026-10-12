package com.justcommit.backend.member.presentation.dto;

import com.justcommit.backend.member.application.LoginCommand;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import static com.justcommit.backend.member.presentation.dto.MemberValidation.EMAIL_REGEX;

public record LoginRequest(
        @NotBlank(message = "이메일을 입력해주세요.")
        @Email(regexp = EMAIL_REGEX, message = "이메일 형식이 올바르지 않습니다.")
        String email,

        // 형식 검증X, 규칙이 바뀌어도 기존 회원 로그인 가능
        @NotBlank(message = "비밀번호를 입력해주세요.")
        String password
        ) {
  public LoginCommand toCommand() {
    return new LoginCommand(email, password);
  }

  // 로그·디버깅 출력에 비밀번호가 찍히지 않도록 가림
  @Override
  public String toString() {
    return "LoginRequest[email=" + email + ", password=****]";
  }
}

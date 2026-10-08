package com.justcommit.backend.member.presentation.dto;

import com.justcommit.backend.member.application.SignupCommand;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import static com.justcommit.backend.member.presentation.dto.MemberValidation.*;

public record SignupRequest(
        @NotBlank(message = "이메일을 입력해주세요.")
        @Email(regexp = EMAIL_REGEX, message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @NotBlank(message = "비밀번호를 입력해주세요.")
        @Pattern(regexp = PASSWORD_REGEX, message = "비밀번호는 영문과 숫자를 포함한 8~20자여야 합니다.")
        String password,

        @NotBlank(message = "닉네임을 입력해주세요.")
        @Pattern(regexp = NICKNAME_REGEX, message = "닉네임은 한글, 영문, 숫자 2~8자여야 합니다.")
        String nickname,

        @NotBlank(message = "휴대폰 번호를 입력해주세요.")
        @Pattern(regexp = PHONE_REGEX, message = "휴대폰 번호는 하이픈 없이 숫자 11자리여야 합니다.")
        String phone
) {
  public SignupCommand toCommand() {
    return new SignupCommand(email, password, nickname, phone);
  }
}

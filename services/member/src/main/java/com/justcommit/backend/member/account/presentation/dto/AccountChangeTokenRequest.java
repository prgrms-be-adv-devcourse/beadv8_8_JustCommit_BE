package com.justcommit.backend.member.account.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

// 계좌 변경 인증번호 확인 요청 (이메일은 로그인한 회원 정보에서 사용하므로 받지 않음)
public record AccountChangeTokenRequest(
        @NotBlank(message = "인증번호를 입력해주세요.")
        @Pattern(regexp = "\\d{6}", message = "인증번호는 6자리 숫자입니다.")
        String code
) {
}

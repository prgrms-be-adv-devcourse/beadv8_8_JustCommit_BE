package com.justcommit.backend.member.account.presentation.dto;

import com.justcommit.backend.member.account.application.AccountChangeCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import static com.justcommit.backend.member.presentation.validation.MemberValidation.ACCOUNT_HOLDER_MAX_LENGTH;
import static com.justcommit.backend.member.presentation.validation.MemberValidation.ACCOUNT_NO_REGEX;
import static com.justcommit.backend.member.presentation.validation.MemberValidation.BANK_CODE_REGEX;

// 계좌 변경 요청 (#413). 계좌 세 값은 가입과 같은 규칙, 토큰은 인증번호 확인으로 받은 일회용 계좌 변경 토큰
public record AccountChangeRequest(
        @NotBlank(message = "계좌 변경 토큰이 필요합니다. 이메일 인증을 먼저 진행해 주세요.")
        String accountChangeToken,

        @NotBlank(message = "은행을 선택해주세요.")
        @Pattern(regexp = BANK_CODE_REGEX, message = "은행 코드는 숫자 3자리여야 합니다.")
        String bankCode,

        @NotBlank(message = "계좌번호를 입력해주세요.")
        @Pattern(regexp = ACCOUNT_NO_REGEX, message = "계좌번호는 하이픈 없이 숫자 10~14자리여야 합니다.")
        String accountNo,

        @NotBlank(message = "예금주를 입력해주세요.")
        @Size(max = ACCOUNT_HOLDER_MAX_LENGTH, message = "예금주는 50자 이하여야 합니다.")
        String accountHolder
) {
  public AccountChangeCommand toCommand() {
    return new AccountChangeCommand(accountChangeToken, bankCode, accountNo, accountHolder);
  }

  // 로그·디버깅 출력에 토큰 · 계좌번호 원문이 찍히지 않도록 가림
  @Override
  public String toString() {
    return "AccountChangeRequest[accountChangeToken=****, bankCode=" + bankCode
        + ", accountNo=****, accountHolder=" + accountHolder + "]";
  }
}

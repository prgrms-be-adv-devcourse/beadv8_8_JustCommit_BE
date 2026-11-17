package com.justcommit.backend.member.account.presentation;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.member.account.application.AccountChangeService;
import com.justcommit.backend.member.account.presentation.dto.AccountChangeRequest;
import com.justcommit.backend.member.account.presentation.dto.AccountChangeTokenRequest;
import com.justcommit.backend.member.account.presentation.dto.AccountChangeTokenResponse;
import com.justcommit.backend.member.auth.presentation.dto.EmailSendResponse;
import com.justcommit.backend.member.domain.exception.EmailCooldownException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

// 내 계좌 변경 API (#413). 모두 로그인 필수이며, 이메일은 요청으로 받지 않고 회원 정보의 값을 쓴다
@RestController
@RequestMapping("/api/v1/users/me/account")
@RequiredArgsConstructor
public class AccountController {

  private final AccountChangeService accountChangeService;

  // 계좌 변경 인증번호 발송 (본문 없음)
  @PostMapping("/verifications")
  public ApiResponse<EmailSendResponse> sendVerificationCode(@AuthenticationPrincipal AuthMember authMember) {
    return ApiResponse.ok("인증번호를 발송했습니다.",
        EmailSendResponse.from(accountChangeService.sendVerificationCode(authMember.memberId())));
  }

  // 인증번호 확인 → 계좌 변경 토큰 발급
  @PostMapping("/changeTokens")
  public ApiResponse<AccountChangeTokenResponse> issueChangeToken(@AuthenticationPrincipal AuthMember authMember,
                                                                  @Valid @RequestBody AccountChangeTokenRequest request) {
    return ApiResponse.ok("이메일 인증이 완료되었습니다.",
        AccountChangeTokenResponse.from(accountChangeService.issueChangeToken(authMember.memberId(), request.code())));
  }

  // 계좌 변경 (성공 시 data 없음. 바뀐 계좌는 GET /api/v1/users/me에서 마스킹해 조회)
  @PatchMapping
  public ApiResponse<Void> changeAccount(@AuthenticationPrincipal AuthMember authMember,
                                         @Valid @RequestBody AccountChangeRequest request) {
    accountChangeService.changeAccount(authMember.memberId(), request.toCommand());
    return ApiResponse.ok("계좌가 변경되었습니다.");
  }

  // 재발송 제한 · 하루 한도: 공통 응답 형식 + Retry-After 헤더(남은 초) (AuthController와 같은 처리)
  // 컨트롤러 안의 @ExceptionHandler는 GlobalExceptionHandler보다 먼저 적용됨
  @ExceptionHandler(EmailCooldownException.class)
  public ResponseEntity<ApiResponse<Void>> handleCooldown(EmailCooldownException e) {
    return ResponseEntity.status(e.getErrorCode().getStatus())
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
            .body(ApiResponse.error(e.getErrorCode()));
  }
}

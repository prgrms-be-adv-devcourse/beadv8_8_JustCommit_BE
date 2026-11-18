package com.justcommit.backend.member.withdrawal.presentation;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.member.auth.presentation.dto.EmailSendResponse;
import com.justcommit.backend.member.domain.exception.EmailCooldownException;
import com.justcommit.backend.member.withdrawal.application.WithdrawalVerificationService;
import com.justcommit.backend.member.withdrawal.presentation.dto.WithdrawalTokenRequest;
import com.justcommit.backend.member.withdrawal.presentation.dto.WithdrawalTokenResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

// 회원 탈퇴 API (#407). 모두 로그인 필수이며, 이메일은 요청으로 받지 않고 회원 정보의 값을 쓴다
// 인증번호 · 탈퇴 토큰은 카카오 회원 재확인용 (로컬 회원은 최종 탈퇴 요청에서 비밀번호로 재확인)
@RestController
@RequestMapping("/api/v1/users/me/withdrawal")
@RequiredArgsConstructor
public class WithdrawalController {

  private final WithdrawalVerificationService withdrawalVerificationService;

  // 탈퇴 인증번호 발송 (본문 없음)
  @PostMapping("/verifications")
  public ApiResponse<EmailSendResponse> sendVerificationCode(@AuthenticationPrincipal AuthMember authMember) {
    return ApiResponse.ok("인증번호 발송을 요청했습니다.",
        EmailSendResponse.from(withdrawalVerificationService.sendVerificationCode(authMember.memberId())));
  }

  // 인증번호 확인 → 탈퇴 토큰 발급
  @PostMapping("/tokens")
  public ApiResponse<WithdrawalTokenResponse> issueToken(@AuthenticationPrincipal AuthMember authMember,
                                                         @Valid @RequestBody WithdrawalTokenRequest request) {
    return ApiResponse.ok("이메일 인증이 완료되었습니다.",
        WithdrawalTokenResponse.from(withdrawalVerificationService.issueToken(authMember.memberId(), request.code())));
  }

  // 재발송 제한 · 하루 한도: 공통 응답 형식 + Retry-After 헤더(남은 초) (AccountController와 같은 처리)
  // 컨트롤러 안의 @ExceptionHandler는 GlobalExceptionHandler보다 먼저 적용됨
  @ExceptionHandler(EmailCooldownException.class)
  public ResponseEntity<ApiResponse<Void>> handleCooldown(EmailCooldownException e) {
    return ResponseEntity.status(e.getErrorCode().getStatus())
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
            .body(ApiResponse.error(e.getErrorCode()));
  }
}

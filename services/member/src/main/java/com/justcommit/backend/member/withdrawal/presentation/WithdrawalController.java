package com.justcommit.backend.member.withdrawal.presentation;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.member.auth.presentation.dto.EmailSendResponse;
import com.justcommit.backend.member.auth.presentation.support.RefreshTokenCookieFactory;
import com.justcommit.backend.member.domain.exception.EmailCooldownException;
import com.justcommit.backend.member.domain.exception.PasswordChangeLockedException;
import com.justcommit.backend.member.withdrawal.application.WithdrawalResult;
import com.justcommit.backend.member.withdrawal.application.WithdrawalService;
import com.justcommit.backend.member.withdrawal.application.WithdrawalVerificationService;
import com.justcommit.backend.member.withdrawal.presentation.dto.WithdrawalEligibilityResponse;
import com.justcommit.backend.member.withdrawal.presentation.dto.WithdrawalRequest;
import com.justcommit.backend.member.withdrawal.presentation.dto.WithdrawalResponse;
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

  private final WithdrawalService withdrawalService;
  private final WithdrawalVerificationService withdrawalVerificationService;
  private final RefreshTokenCookieFactory refreshTokenCookieFactory;

  // 탈퇴 가능 여부 · 차단 사유 (화면 안내용, 결과를 저장하지 않음. 최종 탈퇴에서 다시 확인)
  @GetMapping
  public ApiResponse<WithdrawalEligibilityResponse> check(@AuthenticationPrincipal AuthMember authMember) {
    return ApiResponse.ok("탈퇴 가능 여부를 확인했습니다.",
        WithdrawalEligibilityResponse.from(withdrawalService.check(authMember.memberId())));
  }

  // 최종 탈퇴. 성공하면 이 기기 Refresh 쿠키를 만료 (비밀번호 변경과 같음)
  // revocationCompleted=false여도 탈퇴는 커밋됨 (다른 기기의 로그아웃 반영만 늦어질 수 있음)
  // 실패 · 결과 불명(503 RESULT_UNKNOWN)이면 쿠키를 유지하고, 클라이언트는 자동으로 다시 요청하지 않음
  @PostMapping
  public ResponseEntity<ApiResponse<WithdrawalResponse>> withdraw(@AuthenticationPrincipal AuthMember authMember,
                                                                  @RequestBody WithdrawalRequest request) {
    WithdrawalResult result = withdrawalService.withdraw(
        authMember.memberId(), request.password(), request.withdrawalToken());
    String message = result.revocationCompleted()
        ? "회원 탈퇴가 완료되었습니다."
        : "회원 탈퇴가 완료되었습니다. 다른 기기의 로그아웃이 늦어질 수 있습니다.";
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, refreshTokenCookieFactory.expire().toString())
        .body(ApiResponse.ok(message, WithdrawalResponse.from(result)));
  }

  // 탈퇴 인증번호 발송 (카카오 회원, 본문 없음)
  @PostMapping("/verifications")
  public ApiResponse<EmailSendResponse> sendVerificationCode(@AuthenticationPrincipal AuthMember authMember) {
    return ApiResponse.ok("인증번호 발송을 요청했습니다.",
        EmailSendResponse.from(withdrawalVerificationService.sendVerificationCode(authMember.memberId())));
  }

  // 인증번호 확인 → 탈퇴 토큰 발급 (카카오 회원)
  @PostMapping("/tokens")
  public ApiResponse<WithdrawalTokenResponse> issueToken(@AuthenticationPrincipal AuthMember authMember,
                                                         @Valid @RequestBody WithdrawalTokenRequest request) {
    return ApiResponse.ok("이메일 인증이 완료되었습니다.",
        WithdrawalTokenResponse.from(withdrawalVerificationService.issueToken(authMember.memberId(), request.code())));
  }

  // 재발송 제한 · 하루 한도 / 현재 비밀번호 실패 한도: 공통 응답 형식 + Retry-After 헤더(남은 초)
  // 컨트롤러 안의 @ExceptionHandler는 GlobalExceptionHandler보다 먼저 적용됨 (다른 컨트롤러의 처리기는 적용되지 않음)
  @ExceptionHandler(EmailCooldownException.class)
  public ResponseEntity<ApiResponse<Void>> handleCooldown(EmailCooldownException e) {
    return retryAfter(e.getErrorCode().getStatus().value(), e.getRetryAfterSeconds(), ApiResponse.error(e.getErrorCode()));
  }

  @ExceptionHandler(PasswordChangeLockedException.class)
  public ResponseEntity<ApiResponse<Void>> handlePasswordLocked(PasswordChangeLockedException e) {
    return retryAfter(e.getErrorCode().getStatus().value(), e.getRetryAfterSeconds(), ApiResponse.error(e.getErrorCode()));
  }

  private static ResponseEntity<ApiResponse<Void>> retryAfter(int status, long seconds, ApiResponse<Void> body) {
    return ResponseEntity.status(status)
        .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
        .body(body);
  }
}

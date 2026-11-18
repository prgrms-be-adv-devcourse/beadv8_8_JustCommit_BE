package com.justcommit.backend.member.auth.presentation;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.member.auth.domain.EmailPurpose;
import com.justcommit.backend.member.auth.application.*;
import com.justcommit.backend.member.auth.presentation.dto.*;
import com.justcommit.backend.member.auth.presentation.support.RefreshTokenCookieFactory;
import com.justcommit.backend.member.domain.exception.EmailCooldownException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// 인증 API (이메일 인증, 로그인, 재발급, 로그아웃)
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

  private static final String BEARER_PREFIX = "Bearer ";

  private final EmailVerificationService emailVerificationService;
  private final PasswordResetService passwordResetService;
  private final AccountReminderService accountReminderService;
  private final LoginService loginService;
  private final TokenReissueService tokenReissueService;
  private final LogoutService logoutService;
  private final RefreshTokenCookieFactory refreshTokenCookieFactory;

  @PostMapping("/email/send")
  public ApiResponse<EmailSendResponse> sendEmailCode(@Valid @RequestBody EmailSendRequest request) {
    EmailPurpose purpose = request.purposeOrDefault();
    EmailSendResult result = emailVerificationService.sendCode(request.email(), purpose);
    // 재설정은 실제 발송 여부와 관계없이 같은 안내 (가입 여부 · 로그인 방식이 드러나지 않게)
    String message = purpose == EmailPurpose.PASSWORD_RESET
        ? "재설정 가능한 계정이면 인증번호를 보내드립니다."
        : "인증번호 발송을 요청했습니다.";
    return ApiResponse.ok(message, EmailSendResponse.from(result));
  }

  @PostMapping("/email/verify")
  public ApiResponse<EmailVerifyResponse> verifyEmailCode(@Valid @RequestBody EmailVerifyRequest request) {
    EmailVerifyResult result =
        emailVerificationService.verifyCode(request.email(), request.code(), request.purposeOrDefault());
    // 가입은 data 없이(기존과 같음), 재설정은 일회용 재설정 토큰
    return ApiResponse.ok("이메일 인증이 완료되었습니다.", EmailVerifyResponse.from(result));
  }

  // 비밀번호 재설정 (#412): 재설정 목적 이메일 인증으로 받은 일회용 토큰 + 새 비밀번호
  // 로그인 없이 호출하므로 Refresh 쿠키는 건드리지 않는다 (이 브라우저에 다른 계정 쿠키가 있을 수 있음).
  // 재설정한 계정의 기존 Refresh는 토큰 폐기(#409)로 서버에서 모두 무효가 된다
  @PostMapping("/passwordResets")
  public ApiResponse<PasswordResetResponse> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
    PasswordResetResult result = passwordResetService.reset(request.resetToken(), request.newPassword());
    return ApiResponse.ok("비밀번호를 재설정했습니다.", PasswordResetResponse.from(result));
  }

  // 가입 계정 안내 (#412, 아이디 찾기): 유효한 요청은 가입 여부 · 발송 제한 · SMTP 실패와 관계없이 같은 200
  // (입력 오류 400, Redis · DB 장애는 서버 오류). IP 기준 요청 수 제한은 Gateway에서 429,
  // 같은 이메일의 발송 예약 제한은 인증 메일과 함께 member에서 (걸려도 같은 200)
  @PostMapping("/accountReminders")
  public ApiResponse<Void> sendAccountReminder(@Valid @RequestBody AccountReminderRequest request) {
    accountReminderService.sendReminder(request.email());
    return ApiResponse.ok("가입된 계정이면 안내 메일을 보내드립니다.");
  }

  // 로그인: Access 토큰은 Body, Refresh 토큰은 HttpOnly 쿠키
  // Set-Cookie 헤더를 넣어야 해서 ResponseEntity로 감쌈 (Body 형식은 ApiResponse 동일)
  @PostMapping("/login")
  public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
    LoginResult result = loginService.login(request.toCommand());
    ResponseCookie refreshCookie = refreshTokenCookieFactory.create(result.refreshToken(), result.refreshTokenTtl());

    return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
            .body(ApiResponse.ok("로그인되었습니다.", LoginResponse.from(result)));
  }

  // 재발급: 쿠키의 Refresh 토큰으로 새 Access(Body)·Refresh(쿠키) 발급 (Access 토큰 불필요)
  // required = false: 쿠키가 없을 때 Spring의 400 대신 서비스에서 401 INVALID_REFRESH_TOKEN으로 처리
  @PostMapping("/reissue")
  public ResponseEntity<ApiResponse<TokenReissueResponse>> reissue(
          @CookieValue(name = RefreshTokenCookieFactory.COOKIE_NAME, required = false) String refreshToken) {
    TokenReissueResult result = tokenReissueService.reissue(refreshToken);
    ResponseCookie refreshCookie = refreshTokenCookieFactory.create(result.refreshToken(), result.refreshTokenTtl());

    return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
            .body(ApiResponse.ok("토큰이 재발급되었습니다.", TokenReissueResponse.from(result)));
  }

  // 로그아웃: 이 기기 Refresh 폐기 + 함께 온 Access를 블랙리스트에 등록 + Refresh 쿠키 삭제
  // 두 토큰 모두 선택 (Access는 Authorization 헤더, Refresh는 쿠키). 없거나 유효하지 않아도 200
  // 공개 경로라 Gateway · 보안 필터가 Access를 먼저 거절하지 않음 → 검증은 LogoutService에서
  // Redis 처리 실패는 500 (공통 오류 처리, 이때 쿠키 만료는 포함되지 않음)
  @PostMapping("/logout")
  public ResponseEntity<ApiResponse<Void>> logout(
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @CookieValue(name = RefreshTokenCookieFactory.COOKIE_NAME, required = false) String refreshToken) {
    logoutService.logout(bearerToken(authorization), refreshToken);

    return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, refreshTokenCookieFactory.expire().toString())
            .body(ApiResponse.ok("로그아웃되었습니다."));
  }

  // "Bearer {토큰}"에서 토큰만 꺼냄. 형식이 다르면 Access가 없는 것으로 봄 (공통 보안 필터와 같은 규칙)
  private static String bearerToken(String authorization) {
    if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
      return null;
    }
    return authorization.substring(BEARER_PREFIX.length());
  }

  // 재발송 제한: 공통 응답 형식 + Retry-After 헤더(남은 초)
  // 컨트롤러 안의 @ExceptionHandler는 GlobalExceptionHandler보다 먼저 적용됨
  @ExceptionHandler(EmailCooldownException.class)
  public ResponseEntity<ApiResponse<Void>> handleCooldown(EmailCooldownException e) {
    return ResponseEntity.status(e.getErrorCode().getStatus())
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
            .body(ApiResponse.error(e.getErrorCode()));
  }
}

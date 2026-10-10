package com.justcommit.backend.member.presentation.controller;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.member.application.EmailSendResult;
import com.justcommit.backend.member.application.EmailVerificationService;
import com.justcommit.backend.member.application.LoginResult;
import com.justcommit.backend.member.application.LoginService;
import com.justcommit.backend.member.domain.exception.EmailCooldownException;
import com.justcommit.backend.member.presentation.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// 인증 API (이메일 인증, 로그인 → 이후 재발급·로그아웃도 여기에 추가)
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

  private static final String REFRESH_TOKEN_COOKIE = "refreshToken";
  // 재발급·로그아웃(/api/v1/auth/...) 요청에만 쿠키가 전송되도록 경로 제한
  private static final String REFRESH_TOKEN_COOKIE_PATH = "/api/v1/auth";

  private final EmailVerificationService emailVerificationService;
  private final LoginService loginService;

  @PostMapping("/email/send")
  public ApiResponse<EmailSendResponse> sendEmailCode(@Valid @RequestBody EmailSendRequest request) {
    EmailSendResult result = emailVerificationService.sendCode(request.email());
    return ApiResponse.ok("인증번호를 발송했습니다.", EmailSendResponse.from(result));
  }

  @PostMapping("/email/verify")
  public ApiResponse<Void> verifyEmailCode(@Valid @RequestBody EmailVerifyRequest request) {
    emailVerificationService.verifyCode(request.email(), request.code());
    return ApiResponse.ok("이메일 인증이 완료되었습니다.");
  }

  // 로그인: Access 토큰은 Body, Refresh 토큰은 HttpOnly 쿠키
  // Set-Cookie 헤더를 넣어야 해서 ResponseEntity로 감쌈 (Body 형식은 ApiResponse 동일)
  @PostMapping("/login")
  public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
    LoginResult result = loginService.login(request.toCommand());

    ResponseCookie refreshCookie = ResponseCookie.from(REFRESH_TOKEN_COOKIE, result.refreshToken())
            .httpOnly(true) // JS에서 읽을 수 없음 (XSS 탈취 방지)
            .path(REFRESH_TOKEN_COOKIE_PATH)
            .maxAge(result.refreshTokenTtl()) // Redis TTL과 같은 3시간
            .sameSite("Lax")
            .build(); // HTTP 배포라 Secure는 넣지 않음

    return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
            .body(ApiResponse.ok("로그인되었습니다.", LoginResponse.from(result)));
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

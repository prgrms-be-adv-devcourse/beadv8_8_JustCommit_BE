package com.justcommit.backend.member.presentation.controller;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.member.auth.application.*;
import com.justcommit.backend.member.domain.exception.EmailCooldownException;
import com.justcommit.backend.member.presentation.dto.*;
import com.justcommit.backend.member.presentation.support.RefreshTokenCookieFactory;
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

  private final EmailVerificationService emailVerificationService;
  private final LoginService loginService;
  private final TokenReissueService tokenReissueService;
  private final LogoutService logoutService;
  private final RefreshTokenCookieFactory refreshTokenCookieFactory;

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

  // 로그아웃: Refresh 토큰 폐기 + 쿠키 삭제 (Access 토큰 불필요)
  // 쿠키가 없거나 이미 만료·삭제된 경우에도 200 (쿠키만 지움)
  @PostMapping("/logout")
  public ResponseEntity<ApiResponse<Void>> logout(
          @CookieValue(name = RefreshTokenCookieFactory.COOKIE_NAME, required = false) String refreshToken) {
    logoutService.logout(refreshToken);

    return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, refreshTokenCookieFactory.expire().toString())
            .body(ApiResponse.ok("로그아웃되었습니다."));
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

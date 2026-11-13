package com.justcommit.backend.member.auth.presentation;

import com.justcommit.backend.common.response.ApiResponse;
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

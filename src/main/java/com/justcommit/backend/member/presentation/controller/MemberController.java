package com.justcommit.backend.member.presentation.controller;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.member.application.*;
import com.justcommit.backend.member.auth.application.LoginResult;
import com.justcommit.backend.member.presentation.dto.*;
import com.justcommit.backend.member.presentation.support.RefreshTokenCookieFactory;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

// 회원 API (회원가입, 소셜 회원가입, 닉네임 중복 확인, 내 정보 조회)
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class MemberController {

  private static final String BEARER_PREFIX = "Bearer ";

  private final SignupService signupService;
  private final SocialSignupService socialSignupService;
  private final MyInfoService myInfoService;
  private final RefreshTokenCookieFactory refreshTokenCookieFactory;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
    SignupResult result = signupService.signup(request.toCommand());
    return ApiResponse.created("회원가입이 완료되었습니다.", SignupResponse.from(result));
  }

  // 소셜 회원가입: 가입 토큰(Authorization: Bearer) + 추가정보 → 회원 생성 후 바로 로그인
  // Access 토큰은 Body, Refresh 토큰은 HttpOnly 쿠키
  // required = false: 헤더가 없을 때 Spring의 400 대신 서비스에서 401 INVALID_SIGNUP_TOKEN으로 처리
  @PostMapping("/social")
  public ResponseEntity<ApiResponse<LoginResponse>> socialSignup(
          @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
          @Valid @RequestBody SocialSignupRequest request) {
    LoginResult result = socialSignupService.signup(extractBearerToken(authorization), request.toCommand());
    ResponseCookie refreshCookie = refreshTokenCookieFactory.create(result.refreshToken(), result.refreshTokenTtl());

    return ResponseEntity.status(HttpStatus.CREATED)
            .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
            .body(ApiResponse.created("회원가입이 완료되었습니다.", LoginResponse.from(result)));
  }

  @GetMapping("/checkNickname")
  public ApiResponse<NicknameCheckResponse> checkNickname(@RequestParam String nickname) {
    boolean available = signupService.isNicknameAvailable(nickname);
    return ApiResponse.ok("닉네임 사용 가능 여부를 확인했습니다.", new NicknameCheckResponse(available));
  }

  @GetMapping("/me")
  public ApiResponse<MyInfoResponse> getMyInfo(@AuthenticationPrincipal AuthMember authMember) {
    MyInfoResult result = myInfoService.getMyInfo(authMember.memberId());
    return ApiResponse.ok("내 정보를 조회했습니다.", MyInfoResponse.from(result));
  }

  // Bearer {토큰}에서 토큰만 꺼냄. 형식 다르면 null → 서비스에서 401
  private static String extractBearerToken(String authorization) {
    if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
      return null;
    }
    return authorization.substring(BEARER_PREFIX.length());
  }
}

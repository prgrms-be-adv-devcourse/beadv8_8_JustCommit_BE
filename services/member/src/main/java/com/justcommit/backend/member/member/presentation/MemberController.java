package com.justcommit.backend.member.member.presentation;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.member.auth.application.LoginResult;
import com.justcommit.backend.member.auth.presentation.dto.LoginResponse;
import com.justcommit.backend.member.member.application.*;
import com.justcommit.backend.member.member.presentation.dto.*;
import com.justcommit.backend.member.auth.presentation.support.RefreshTokenCookieFactory;
import com.justcommit.backend.member.domain.exception.PasswordChangeLockedException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

// 회원 API (회원가입, 소셜 회원가입, 닉네임 · 휴대폰 중복 확인, 내 정보 조회, 닉네임 · 비밀번호 변경)
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class MemberController {

  private static final String BEARER_PREFIX = "Bearer ";

  private final SignupService signupService;
  private final SocialSignupService socialSignupService;
  private final MyInfoService myInfoService;
  private final NicknameChangeService nicknameChangeService;
  private final PasswordChangeService passwordChangeService;
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

  @GetMapping("/nicknameAvailability")
  public ApiResponse<NicknameCheckResponse> checkNickname(@RequestParam String nickname) {
    boolean available = signupService.isNicknameAvailable(nickname);
    return ApiResponse.ok("닉네임 사용 가능 여부를 확인했습니다.", new NicknameCheckResponse(available));
  }

  // 형식 검증은 가입 요청과 같은 PHONE_REGEX. 실패하면 BindException → 400 INVALID_FIELD_ERROR
  @GetMapping("/phoneAvailability")
  public ApiResponse<PhoneCheckResponse> checkPhone(@Valid @ModelAttribute PhoneCheckRequest request) {
    boolean available = signupService.isPhoneAvailable(request.phone());
    return ApiResponse.ok("휴대폰 번호 사용 가능 여부를 확인했습니다.", new PhoneCheckResponse(available));
  }

  @GetMapping("/me")
  public ApiResponse<MyInfoResponse> getMyInfo(@AuthenticationPrincipal AuthMember authMember) {
    MyInfoResult result = myInfoService.getMyInfo(authMember.memberId());
    return ApiResponse.ok("내 정보를 조회했습니다.", MyInfoResponse.from(result));
  }

  // 닉네임 변경: 지금 닉네임과 같으면 그대로 200 (이벤트 없음)
  @PatchMapping("/me/nickname")
  public ApiResponse<NicknameChangeResponse> changeNickname(@AuthenticationPrincipal AuthMember authMember,
                                                            @Valid @RequestBody NicknameChangeRequest request) {
    NicknameChangeResult result = nicknameChangeService.changeNickname(authMember.memberId(), request.nickname());
    return ApiResponse.ok("닉네임을 변경했습니다.", NicknameChangeResponse.from(result));
  }

  // 비밀번호 변경: 성공(200)이면 이 기기의 Refresh 쿠키를 만료. 프론트는 Access를 지우고 로그인 화면으로 이동
  // revocationCompleted=false면 다른 기기의 로그아웃 반영이 늦어질 수 있음 (재처리가 보관 기한 안에 기록)
  // 결과 불명(503 RESULT_UNKNOWN)이면 쿠키를 유지하고, 클라이언트는 자동으로 다시 요청하지 않음
  @PatchMapping("/me/password")
  public ResponseEntity<ApiResponse<PasswordChangeResponse>> changePassword(
      @AuthenticationPrincipal AuthMember authMember,
      @Valid @RequestBody PasswordChangeRequest request) {
    PasswordChangeResult result = passwordChangeService.changePassword(
        authMember.memberId(), request.currentPassword(), request.newPassword());
    String message = result.revocationCompleted()
        ? "비밀번호를 변경했습니다."
        : "비밀번호를 변경했습니다. 다른 기기의 로그아웃이 늦어질 수 있습니다.";
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, refreshTokenCookieFactory.expire().toString())
        .body(ApiResponse.ok(message, PasswordChangeResponse.from(result)));
  }

  // 현재 비밀번호를 여러 번 틀려 막힌 변경 요청 (#412): 공통 응답 형식 + Retry-After 헤더(남은 초)
  // 컨트롤러 안의 @ExceptionHandler는 GlobalExceptionHandler보다 먼저 적용됨
  @ExceptionHandler(PasswordChangeLockedException.class)
  public ResponseEntity<ApiResponse<Void>> handlePasswordChangeLocked(PasswordChangeLockedException e) {
    return ResponseEntity.status(e.getErrorCode().getStatus())
        .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
        .body(ApiResponse.error(e.getErrorCode()));
  }

  // Bearer {토큰}에서 토큰만 꺼냄. 형식 다르면 null → 서비스에서 401
  private static String extractBearerToken(String authorization) {
    if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
      return null;
    }
    return authorization.substring(BEARER_PREFIX.length());
  }
}

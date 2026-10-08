package com.justcommit.backend.member.presentation.controller;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.member.application.SignupResult;
import com.justcommit.backend.member.application.SignupService;
import com.justcommit.backend.member.presentation.dto.NicknameCheckResponse;
import com.justcommit.backend.member.presentation.dto.SignupRequest;
import com.justcommit.backend.member.presentation.dto.SignupResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

// 회원 API (회원가입, 닉네임 중복 확인 → 이후 내 정보 조회 등 추가)
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class MemberController {

  private final SignupService signupService;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
    SignupResult result = signupService.signup(request.toCommand());
    return ApiResponse.created("회원가입이 완료되었습니다.", SignupResponse.from(result));
  }

  @GetMapping("/checkNickname")
  public ApiResponse<NicknameCheckResponse> checkNickname(@RequestParam String nickname) {
    boolean available = signupService.isNicknameAvailable(nickname);
    return ApiResponse.ok("닉네임 사용 가능 여부를 확인했습니다.", new NicknameCheckResponse(available));
  }
}

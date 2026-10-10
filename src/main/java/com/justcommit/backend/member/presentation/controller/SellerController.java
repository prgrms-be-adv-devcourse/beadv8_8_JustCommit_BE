package com.justcommit.backend.member.presentation.controller;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.member.application.SellerRegisterService;
import com.justcommit.backend.member.presentation.dto.SellerRegisterRequest;
import com.justcommit.backend.member.presentation.dto.SellerResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sellers")
@RequiredArgsConstructor
public class SellerController {

  private final SellerRegisterService sellerRegisterService;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<SellerResponse> register(@AuthenticationPrincipal AuthMember authMember,
                                              @Valid @RequestBody SellerRegisterRequest request) {
    SellerResponse response =
            SellerResponse.from(sellerRegisterService.register(request.toCommand(authMember.memberId())));
    return ApiResponse.created("판매자 등록이 완료되었습니다.", response);
  }
}

package com.justcommit.backend.member.seller.presentation;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.member.seller.application.SellerProfileService;
import com.justcommit.backend.member.seller.application.SellerRegisterService;
import com.justcommit.backend.member.seller.application.SellerResult;
import com.justcommit.backend.member.seller.presentation.dto.SellerIntroChangeRequest;
import com.justcommit.backend.member.seller.presentation.dto.SellerRegisterRequest;
import com.justcommit.backend.member.seller.presentation.dto.SellerResponse;
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
  private final SellerProfileService sellerProfileService;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<SellerResponse> register(@AuthenticationPrincipal AuthMember authMember,
                                              @Valid @RequestBody SellerRegisterRequest request) {
    SellerResponse response =
            SellerResponse.from(sellerRegisterService.register(request.toCommand(authMember.memberId())));
    return ApiResponse.created("판매자 등록이 완료되었습니다.", response);
  }

  // 판매자 공개 정보 (로그인 없이 조회). 숫자 id만 받아 sellers/me와 겹치지 않음
  @GetMapping("/{sellerId:[0-9]+}")
  public ApiResponse<SellerResponse> getSeller(@PathVariable Long sellerId) {
    return ApiResponse.ok("판매자 정보를 조회했습니다.", SellerResponse.from(sellerProfileService.getSeller(sellerId)));
  }

  // 본인 소개글 수정 (판매자만). 회원 ID는 인증 정보에서만 꺼냄
  @PatchMapping("/me/intro")
  public ApiResponse<SellerResponse> changeIntro(@AuthenticationPrincipal AuthMember authMember,
                                                  @Valid @RequestBody SellerIntroChangeRequest request) {
    SellerResult result = sellerProfileService.changeIntro(authMember.memberId(), request.introOrNull());
    return ApiResponse.ok("소개글을 수정했습니다.", SellerResponse.from(result));
  }
}

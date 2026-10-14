package com.justcommit.backend.member.presentation.controller;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.member.application.AddressResult;
import com.justcommit.backend.member.application.AddressService;
import com.justcommit.backend.member.presentation.dto.AddressCreateRequest;
import com.justcommit.backend.member.presentation.dto.AddressResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// 내 배송지 API (목록, 등록, 기본 배송지 변경)
@RestController
@RequestMapping("/api/v1/users/me/addresses")
@RequiredArgsConstructor
public class AddressController {

  private final AddressService addressService;

  @GetMapping
  public ApiResponse<List<AddressResponse>> getAddresses(@AuthenticationPrincipal AuthMember authMember) {
    List<AddressResponse> responses = addressService.getAddresses(authMember.memberId()).stream()
            .map(AddressResponse::from)
            .toList();
    return ApiResponse.ok("배송지 목록을 조회했습니다.", responses);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<AddressResponse> addAddress(@AuthenticationPrincipal AuthMember authMember,
                                                 @Valid @RequestBody AddressCreateRequest request) {
    AddressResult result = addressService.addAddress(authMember.memberId(), request.toCommand());
    return ApiResponse.created("배송지를 등록했습니다.", AddressResponse.from(result));
  }

  @PatchMapping("/{addressId}/default")
  public ApiResponse<AddressResponse> changeDefault(@AuthenticationPrincipal AuthMember authMember,
                                                    @PathVariable Long addressId) {
    AddressResult result = addressService.changeDefault(authMember.memberId(), addressId);
    return ApiResponse.ok("기본 배송지를 변경했습니다.", AddressResponse.from(result));
  }
}

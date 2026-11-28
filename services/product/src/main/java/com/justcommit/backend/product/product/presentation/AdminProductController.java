package com.justcommit.backend.product.product.presentation;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.common.response.PagingResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.product.product.application.AdminProductService;
import com.justcommit.backend.product.product.presentation.dto.AdminProductHiddenRequest;
import com.justcommit.backend.product.product.presentation.dto.AdminProductResponse;
import com.justcommit.backend.product.product.presentation.dto.AdminProductSearchRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 관리자 상품 관리 (#623, ADMIN만, SecurityConfig). docs/services/product.md 「상품 정책」
@RestController
@Tag(name = "admin-product-controller", description = "관리자 상품 목록 · 강제 숨김 API")
@RequestMapping("/api/v1/admin/products")
@RequiredArgsConstructor
public class AdminProductController {

    private final AdminProductService adminProductService;

    @Operation(summary = "관리자 상품 목록",
            description = "삭제되지 않은 모든 상품을 최신 등록 순으로 조회합니다(숨긴 상품 포함). keyword(제목) · sellerId · status · "
                    + "adminHidden(강제 숨김 여부)으로 거를 수 있습니다. hidden은 판매자 숨김, adminHidden은 관리자 강제 숨김입니다.")
    @GetMapping
    public ApiResponse<PagingResponse<AdminProductResponse>> getProducts(
            @ModelAttribute AdminProductSearchRequest request) {
        return ApiResponse.ok("관리자 상품 목록을 조회했습니다.",
                PagingResponse.from(adminProductService.getProducts(request)));
    }

    @Operation(summary = "관리자 강제 숨김 · 해제",
            description = "hidden=true면 강제 숨김(reason 필수, 200자 이하, 판매자에게 알림과 함께 보임), false면 해제합니다. "
                    + "판매자 숨김과 따로라 판매자는 강제 숨김을 풀 수 없고, 관리자는 판매자 숨김을 풀 수 없습니다. "
                    + "이미 같은 상태면 그대로 200입니다. 삭제된 상품은 409 PRODUCT_NOT_HIDEABLE입니다.")
    @PatchMapping("/{productId}/hidden")
    public ApiResponse<Void> changeHidden(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long productId,
            @RequestBody @Valid AdminProductHiddenRequest request) {
        adminProductService.changeHidden(authMember.memberId(), productId, request.hidden(), request.reason());
        return ApiResponse.ok(request.hidden() ? "상품을 강제 숨김했습니다." : "상품 강제 숨김을 해제했습니다.");
    }
}

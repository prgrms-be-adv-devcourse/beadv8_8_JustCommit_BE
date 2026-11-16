package com.justcommit.backend.product.review.presentation;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.product.review.application.SellerImageReviewService;
import com.justcommit.backend.product.review.presentation.dto.AppealRequest;
import com.justcommit.backend.product.review.presentation.dto.SellerImageReviewHistoryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 판매자 검수 API (본인 상품만, 남의 상품은 404). docs/flows/image-review.md 「판매자 API」
@RestController
@Tag(name = "seller-image-review-controller", description = "판매자 사진 검수 기록 · 이의 신청 API")
@RequestMapping("/api/v1/products/{productId}")
@RequiredArgsConstructor
public class SellerImageReviewController {

    private final SellerImageReviewService sellerImageReviewService;

    @Operation(summary = "내 상품 검수 기록",
            description = "검수 버전별 상태(검수 중 · 관리자 확인 중 · 확인 지연 · 통과 · 반려) · 반려 사유와 문구 · 판정 주체, "
                    + "남은 재검수 횟수 · 이의 신청 가능 여부 · 기한 초과 재요청 가능 여부를 조회합니다.")
    @GetMapping("/imageReviews")
    public ApiResponse<SellerImageReviewHistoryResponse> getImageReviews(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long productId) {

        return ApiResponse.ok("검수 기록을 조회했습니다.", sellerImageReviewService.history(authMember.memberId(), productId));
    }

    @Operation(summary = "기한 초과 재요청",
            description = "관리자 확인이 늦어져 반려(REVIEW_EXPIRED)된 상품을 같은 내용으로 다시 검수 요청합니다. "
                    + "재검수 · 등록 제한에 세지 않습니다. 다른 상태면 409 RESUBMIT_NOT_ALLOWED입니다.")
    @PostMapping("/imageReviews")
    public ApiResponse<Void> resubmitAfterExpiry(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long productId) {

        sellerImageReviewService.resubmitAfterExpiry(authMember.memberId(), productId);
        return ApiResponse.ok("다시 검수를 요청했습니다.");
    }

    @Operation(summary = "이의 신청",
            description = "자동 반려가 틀렸을 때 수정 없이 관리자 확인을 요청합니다. memo 필수(200자 이하), 같은 검수 버전으로 1번. "
                    + "관리자 반려 · 기한 초과 반려는 409 APPEAL_NOT_ALLOWED, 두 번째는 409 APPEAL_ALREADY_USED입니다.")
    @PostMapping("/appeals")
    public ApiResponse<Void> appeal(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long productId,
            @RequestBody @Valid AppealRequest request) {

        sellerImageReviewService.appeal(authMember.memberId(), productId, request.memo());
        return ApiResponse.ok("이의 신청을 접수했습니다.");
    }
}

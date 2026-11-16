package com.justcommit.backend.product.review.presentation;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.common.response.PagingResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.product.review.application.AdminImageReviewService;
import com.justcommit.backend.product.review.presentation.dto.AdminImageReviewResponse;
import com.justcommit.backend.product.review.presentation.dto.AdminReviewDecisionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 관리자 검수 확인 (ADMIN만, SecurityConfig). docs/flows/image-review.md 「관리자 수동 검수」
@RestController
@Validated
@Tag(name = "admin-image-review-controller", description = "관리자 사진 검수 확인 API")
@RequestMapping("/api/v1/admin/imageReviews")
@RequiredArgsConstructor
public class AdminImageReviewController {

    private final AdminImageReviewService adminImageReviewService;

    @Operation(summary = "관리자 확인 대기 목록",
            description = "자동 검수가 관리자 확인으로 보낸 상품을 오래된 순으로 조회합니다. status는 MANUAL만 지원합니다. "
                    + "12시간이 지나면 delayed=true, expiresAt(24시간)까지 처리하지 않으면 REVIEW_EXPIRED로 자동 반려됩니다.")
    @GetMapping
    public ApiResponse<PagingResponse<AdminImageReviewResponse>> getAwaitingReviews(
            @RequestParam(defaultValue = "MANUAL") AdminReviewStatusFilter status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        return ApiResponse.ok("관리자 확인 대기 목록을 조회했습니다.",
                PagingResponse.from(adminImageReviewService.findAwaiting(PageRequest.of(page, size))));
    }

    @Operation(summary = "관리자 검수 처리",
            description = "통과(APPROVED)면 판매 중, 반려(REJECTED)면 반려됩니다. 반려는 rejectReason 필수(REVIEW_EXPIRED 제외), "
                    + "OTHER면 memo 필수(200자 이하, 판매자에게 그대로 보임). 다른 관리자가 먼저 처리했으면 409 REVIEW_ALREADY_PROCESSED, "
                    + "그사이 판매자가 수정·삭제했으면 409 REVIEW_OUTDATED입니다.")
    @PatchMapping("/{reviewId}")
    public ApiResponse<Void> decide(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long reviewId,
            @RequestBody @Valid AdminReviewDecisionRequest request) {

        adminImageReviewService.decide(reviewId, authMember.memberId(), request.decision(), request.rejectReason(), request.memo());
        return ApiResponse.ok("검수를 처리했습니다.");
    }

    // 목록 조회 조건 (지금은 관리자 확인 대기만)
    public enum AdminReviewStatusFilter {
        MANUAL
    }
}

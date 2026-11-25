package com.justcommit.backend.market.review.presentation;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.common.response.PagingResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.market.review.application.ReviewReadService;
import com.justcommit.backend.market.review.application.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reviews")
@RequiredArgsConstructor
public class ReviewController {

  private final ReviewService reviewService;
  private final ReviewReadService reviewReadService;

  // 리뷰 작성 (#582). 로그인 필요 (공개 경로 아님)
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<ReviewCreateResponse> create(
    @AuthenticationPrincipal AuthMember member,
    @Valid @RequestBody ReviewCreateRequest request
  ) {
    Long reviewId = reviewService.create(member.memberId(), request.sellerOrderId(), request.rating(),
      request.content(), request.tags());
    return ApiResponse.created("리뷰를 작성했습니다.", new ReviewCreateResponse(reviewId));
  }

  // 판매자 리뷰 목록 (#583). 비회원도 조회 (공개 경로는 #581). sellerId 누락 · 형식 오류는 공통 처리로 400
  @GetMapping
  public ApiResponse<PagingResponse<SellerReviewResponse>> sellerReviews(
    @RequestParam Long sellerId,
    @ModelAttribute SellerReviewListRequest request
  ) {
    return ApiResponse.ok("판매자 리뷰를 조회했습니다.", reviewReadService.sellerReviews(sellerId, request.toPageable()));
  }

  // 판매자 리뷰 집계 (#583). 비회원도 조회 (공개 경로는 #581)
  @GetMapping("/summary")
  public ApiResponse<ReviewSummaryResponse> summary(@RequestParam Long sellerId) {
    return ApiResponse.ok("판매자 리뷰 집계를 조회했습니다.", reviewReadService.summary(sellerId));
  }

  // 내 리뷰 목록 (#584). 로그인 필요
  @GetMapping("/me")
  public ApiResponse<PagingResponse<MyReviewResponse>> myReviews(
    @AuthenticationPrincipal AuthMember member,
    @ModelAttribute MyReviewListRequest request
  ) {
    return ApiResponse.ok("내 리뷰를 조회했습니다.", reviewReadService.myReviews(member.memberId(), request.toPageable()));
  }
}

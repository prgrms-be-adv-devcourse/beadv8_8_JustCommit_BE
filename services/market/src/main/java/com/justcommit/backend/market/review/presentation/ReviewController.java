package com.justcommit.backend.market.review.presentation;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.market.review.application.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reviews")
@RequiredArgsConstructor
public class ReviewController {

  private final ReviewService reviewService;

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
}

package com.justcommit.backend.product.product.presentation.dto;

import com.justcommit.backend.product.ProductStatus;

import java.util.Map;

// 내 판매 목록 탭 개수 + 오늘 쓴 등록 · 사진 검수 횟수와 하루 한도
// 오늘 횟수는 hidden 조건과 상관없이 셈 (등록 제한 · 하루 사진 검수 제한과 같은 기준)
public record MyProductCountResponse(
        long total,
        long reviewPending,
        long rejected,
        long active,
        long paymentPending,
        long soldOut,
        long todayRegistered,           // 오늘 등록한 상품 수 (한국 시간 자정부터, 삭제한 상품 포함)
        int dailyRegisterLimit,         // 하루 등록 한도 (20)
        long todayPhotoReviews,         // 오늘 사진 검수 횟수 (글 검사 · 기한 초과 다음 검수 제외)
        int dailyPhotoReviewLimit       // 하루 사진 검수 한도 (30)
) {
    public static MyProductCountResponse of(Map<ProductStatus, Long> counts,
                                            long todayRegistered, int dailyRegisterLimit,
                                            long todayPhotoReviews, int dailyPhotoReviewLimit) {
        long reviewPending = counts.getOrDefault(ProductStatus.REVIEW_PENDING, 0L);
        long rejected = counts.getOrDefault(ProductStatus.REJECTED, 0L);
        long active = counts.getOrDefault(ProductStatus.ACTIVE, 0L);
        long paymentPending = counts.getOrDefault(ProductStatus.PAYMENT_PENDING, 0L);
        long soldOut = counts.getOrDefault(ProductStatus.SOLD_OUT, 0L);
        long total = reviewPending + rejected + active + paymentPending + soldOut;
        return new MyProductCountResponse(total, reviewPending, rejected, active, paymentPending, soldOut,
                todayRegistered, dailyRegisterLimit, todayPhotoReviews, dailyPhotoReviewLimit);
    }
}

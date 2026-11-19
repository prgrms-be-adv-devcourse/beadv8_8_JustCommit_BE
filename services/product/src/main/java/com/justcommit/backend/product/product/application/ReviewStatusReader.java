package com.justcommit.backend.product.product.application;

import com.justcommit.backend.product.product.domain.Product;

import java.util.List;
import java.util.Map;

// 판매자 화면(내 상품 목록 · 개수)에 보여 줄 사진 검수 정보 (구현: review.application.SellerImageReviewService)
// ReviewRequester와 같은 이유로 product 쪽에 둠 → product ↔ review 순환 의존 제거
public interface ReviewStatusReader {

    // 오늘 사진 검수 횟수와 하루 한도 (한도 확인과 같은 기준: 한국 시간 자정, 글 검사 · 기한 초과 다음 검수 제외)
    DailyUsage todayPhotoReviews(Long sellerId);

    // 상품마다 지금 검수 버전의 검수 결과. 검수 기록이 없는 상품은 빠짐. 목록 한 페이지를 쿼리 한 번으로 조회
    Map<Long, LatestReview> latestReviews(List<Product> products);

    // 오늘 쓴 횟수 · 하루 한도
    record DailyUsage(long used, int limit) {
    }

    // 판매자 화면의 검수 배지 · 반려 줄 (값의 뜻은 SellerImageReviewHistoryResponse.Item과 같음)
    record LatestReview(
            String status,          // IN_REVIEW · ADMIN_CHECK · ADMIN_CHECK_DELAYED · APPROVED · REJECTED
            String rejectReason,    // 반려 사유 코드 (반려가 아니면 null)
            String rejectMessage,   // 판매자에게 보여 줄 문구 (OTHER면 관리자가 입력한 내용)
            String decidedBy        // ENGINE · ADMIN · DEADLINE, 아직 판정 전이면 null
    ) {
    }
}

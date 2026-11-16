package com.justcommit.backend.product.review.presentation.dto;

import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.domain.ProductImage;
import com.justcommit.backend.product.review.domain.ImageReview;
import com.justcommit.backend.product.species.domain.Species;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

// 관리자 확인 목록 한 줄: 사진 · 엔진 결과 · 판매자 품종 · 관리자 확인 사유 · 기한
public record AdminImageReviewResponse(
        Long reviewId,
        Long productId,
        int reviewVersion,
        Long sellerId,
        String title,
        String description,
        List<String> imageUrls,
        Long speciesId,
        String speciesName,
        String speciesScientificName,
        boolean otherSpecies,           // 판매자가 「기타」 품종을 고름
        String scope,                   // FULL(사진 검수) · TEXT_ONLY(판매 중 상품의 글 검사)
        String manualReason,            // 관리자 확인으로 보낸 이유 (BANNED_SUSPECTED · SPECIES_MISMATCH · ENGINE_ERROR …)
        String detail,                  // 걸린 금지 품종 · 단어, 불일치 학명
        JsonNode engineResult,          // 유해 여부 · 식물 여부 · 판매 형태 · 후보 · 확신도 (엔진 오류면 일부 null)
        String rejectReason,            // 이의 신청이면 원래 자동 반려 사유
        String appealMemo,              // 판매자의 이의 신청 메모 (이의 신청이 아니면 null)
        LocalDateTime appealedAt,
        LocalDateTime reviewedAt,       // 관리자 확인으로 간 시각 (이의 신청이면 이의 신청 시각)
        LocalDateTime expiresAt,        // 이때까지 처리하지 않으면 REVIEW_EXPIRED로 자동 반려
        boolean delayed                 // 12시간이 지남
) {

    public static AdminImageReviewResponse of(ImageReview review, Product product, JsonNode engineResult,
                                              LocalDateTime expiresAt, boolean delayed) {
        Species species = product.getSpecies();
        return new AdminImageReviewResponse(
                review.getId(), review.getProductId(), review.getReviewVersion(), review.getSellerId(),
                product.getTitle(), product.getDescription(), product.getImages().stream().map(ProductImage::getS3Url).toList(),
                species.getId(), species.getName(), species.getScientificName(), species.isOther(),
                review.getScope().name(),
                review.getManualReason() == null ? null : review.getManualReason().name(),
                review.getDetail(), engineResult,
                review.getRejectReason() == null ? null : review.getRejectReason().name(),
                review.getAppealMemo(), review.getAppealedAt(),
                review.getReviewedAt(), expiresAt, delayed);
    }
}

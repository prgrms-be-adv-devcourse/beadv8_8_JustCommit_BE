package com.justcommit.backend.product.product.presentation.dto;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.justcommit.backend.product.product.application.ReviewStatusReader.LatestReview;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.species.domain.Species;

public record MyProductResponse (
        Long productId,
        String title,
        BigDecimal price,
        String thumbnailUrl,
        Long speciesId,
        String speciesName,
        String status,
        boolean hidden,
        LocalDateTime createdAt,
        // 지금 검수 버전의 검수 결과 (검수 기록이 없으면 모두 null). 값의 뜻은 GET products/{id}/imageReviews와 같음
        String reviewStatus,        // IN_REVIEW · ADMIN_CHECK · ADMIN_CHECK_DELAYED · APPROVED · REJECTED
        String rejectReason,
        String rejectMessage,
        String decidedBy            // ENGINE · ADMIN · DEADLINE
){

    // review: 지금 검수 버전의 검수 결과, 없으면 null
    public static MyProductResponse of(Product product, LatestReview review){
        Species species = product.getSpecies();
        return new MyProductResponse(
                product.getId(), product.getTitle(), product.getPrice(), product.getThumbnailUrl(),
                species.getId(), species.getName(),
                product.getStatus().name(), product.isHidden(), product.getCreatedAt(),
                review == null ? null : review.status(),
                review == null ? null : review.rejectReason(),
                review == null ? null : review.rejectMessage(),
                review == null ? null : review.decidedBy()
        );
    }
}

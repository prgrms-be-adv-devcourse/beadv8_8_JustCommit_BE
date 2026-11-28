package com.justcommit.backend.product.product.presentation.dto;

import com.justcommit.backend.product.product.domain.Product;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 관리자 상품 목록 한 줄. hidden은 판매자 숨김, adminHidden은 관리자 강제 숨김 (둘은 따로)
public record AdminProductResponse(
        Long productId,
        String title,
        BigDecimal price,
        String thumbnailUrl,
        String speciesName,
        String status,
        Long sellerId,
        String sellerNickname,      // 회원 복제본의 닉네임 (복제본이 없으면 「알 수 없는 회원」)
        boolean hidden,
        boolean adminHidden,
        String adminHiddenReason,
        LocalDateTime adminHiddenAt,
        LocalDateTime createdAt
) {

    public static AdminProductResponse of(Product product, String sellerNickname) {
        return new AdminProductResponse(
                product.getId(), product.getTitle(), product.getPrice(), product.getThumbnailUrl(),
                product.getSpecies().getName(), product.getStatus().name(),
                product.getSellerId(), sellerNickname,
                product.isHidden(), product.isAdminHidden(), product.getAdminHiddenReason(), product.getAdminHiddenAt(),
                product.getCreatedAt());
    }
}

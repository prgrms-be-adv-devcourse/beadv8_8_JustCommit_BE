package com.justcommit.backend.product.product.presentation.dto;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.domain.ProductImage;

public record ProductDetailResponse(
        Long productId,
        Long sellerId,
        String sellerName,
        String title,
        String description,
        BigDecimal price,
        Integer heightCm,
        String potSize,
        String growNote,
        String status,
        boolean hidden,             // 판매자 숨김
        boolean adminHidden,        // 관리자 강제 숨김 (#623). 다른 사람에게는 403 PRODUCT_ADMIN_HIDDEN이라 판매자 본인만 true를 봄
        String adminHiddenReason,
        ProductSpeciesResponse species,
        List<String> imageUrls,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static ProductDetailResponse from(Product product, String sellerName) {
        return new ProductDetailResponse(
                product.getId(),
                product.getSellerId(),
                sellerName,
                product.getTitle(),
                product.getDescription(),
                product.getPrice(),
                product.getHeightCm(),
                product.getPotSize(),
                product.getGrowNote(),
                product.getStatus().name(),
                product.isHidden(),
                product.isAdminHidden(),
                product.getAdminHiddenReason(),
                ProductSpeciesResponse.from(product.getSpecies()),
                product.getImages().stream().map(ProductImage::getS3Url).toList(),
                product.getCreatedAt(),
                product.getUpdatedAt());
    }

}
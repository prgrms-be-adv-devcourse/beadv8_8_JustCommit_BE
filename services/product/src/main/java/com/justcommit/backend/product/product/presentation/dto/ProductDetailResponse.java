package com.justcommit.backend.product.product.presentation.dto;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.domain.ProductPicture;

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
        boolean hidden,
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
                ProductSpeciesResponse.from(product.getSpecies()),
                product.getPictures().stream().map(ProductPicture::getS3Url).toList(),
                product.getCreatedAt(),
                product.getUpdatedAt());
    }

}
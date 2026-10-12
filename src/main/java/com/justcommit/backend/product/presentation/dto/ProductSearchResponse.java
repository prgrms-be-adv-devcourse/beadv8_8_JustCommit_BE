package com.justcommit.backend.product.presentation.dto;

import com.justcommit.backend.product.domain.Product;
import com.justcommit.backend.product.domain.Species;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProductSearchResponse (
        Long productId,
        String title,
        BigDecimal price,
        String thumbnailUrl,
        Long speciesId,
        String speciesName,
        String category,
        String status,
        LocalDateTime createdAt
){
    public static ProductSearchResponse from(Product product) {
        Species species = product.getSpecies();
        return new ProductSearchResponse(
                product.getId(),
                product.getTitle(),
                product.getPrice(),
                product.getThumbnailUrl(),
                species.getId(),
                species.getName(),
                species.getCategory() == null ? null : species.getCategory().name(),
                product.getStatus().name(),
                product.getCreatedAt());
    }
}

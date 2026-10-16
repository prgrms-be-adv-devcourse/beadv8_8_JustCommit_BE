package com.justcommit.backend.product.product.presentation.dto;


import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.species.domain.Species;

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
        String light,
        String watering,
        String difficulty,
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
                nameOf(species.getCategory()),
                nameOf(species.getLight()),
                nameOf(species.getWatering()),
                nameOf(species.getDifficulty()),
                product.getStatus().name(),
                product.getCreatedAt());
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }
}

package com.justcommit.backend.product.presentation.dto;

import com.justcommit.backend.product.domain.Product;
import com.justcommit.backend.product.domain.Species;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record MyProductResponse (
        Long productId,
        String title,
        BigDecimal price,
        String thumbnailUrl,
        Long speciesId,
        String speciesName,
        String status,
        boolean hidden,
        LocalDateTime createdAt
){

    public static MyProductResponse from (Product product){
        Species species = product.getSpecies();
        return new MyProductResponse(
                product.getId(), product.getTitle(), product.getPrice(), product.getThumbnailUrl(),
                species.getId(), species.getName(),
                product.getStatus().name(), product.isHidden(), product.getCreatedAt()
        );
    }
}

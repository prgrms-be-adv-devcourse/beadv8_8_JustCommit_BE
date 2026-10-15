package com.justcommit.backend.product.product.presentation.dto;


import java.math.BigDecimal;
import java.time.LocalDateTime;
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

package com.justcommit.backend.product.presentation.dto;

import com.justcommit.backend.product.domain.Species;

public record SpeciesSearchResponse(
        Long speciesId,
        String name,
        String category,
        String thumbnailUrl
) {

    public static SpeciesSearchResponse from(Species species) {
        return new SpeciesSearchResponse(
                species.getId(),
                species.getName(),
                species.getCategory() == null ? null : species.getCategory().name(),
                species.getThumbnailUrl());
    }
}
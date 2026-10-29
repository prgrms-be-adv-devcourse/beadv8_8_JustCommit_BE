package com.justcommit.backend.product.product.presentation.dto;

import com.justcommit.backend.product.species.domain.Species;


public record ProductSpeciesResponse(
        Long speciesId,
        String name,
        String category,
        String light,
        String watering,
        String difficulty

) {
    public static ProductSpeciesResponse from(Species species) {
        return new ProductSpeciesResponse(
                species.getId(),
                species.getName(),
                nameOf(species.getCategory()),
                nameOf(species.getLight()),
                nameOf(species.getWatering()),
                nameOf(species.getDifficulty()));
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
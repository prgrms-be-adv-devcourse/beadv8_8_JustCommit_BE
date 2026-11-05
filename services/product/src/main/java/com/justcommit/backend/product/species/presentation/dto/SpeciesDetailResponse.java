package com.justcommit.backend.product.species.presentation.dto;


import java.util.List;
import com.justcommit.backend.product.species.domain.Species;
import com.justcommit.backend.product.species.domain.SpeciesImage;

public record SpeciesDetailResponse(
        Long speciesId,
        String name,
        String scientificName,
        String category,
        String light,
        String watering,
        String difficulty,
        String color,
        boolean isBanned,
        List<ImageResponse> pictures
) {
    public static SpeciesDetailResponse from(Species species) {
        return new SpeciesDetailResponse(
                species.getId(),
                species.getName(),
                species.getScientificName(),
                nameOf(species.getCategory()),
                nameOf(species.getLight()),
                nameOf(species.getWatering()),
                nameOf(species.getDifficulty()),
                nameOf(species.getColor()),
                species.isBanned(),
                species.getImages().stream()
                        .map(ImageResponse::from)
                        .toList());
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }

    public record ImageResponse(String s3Url, int sortOrder) {

        static ImageResponse from(SpeciesImage image) {
            return new ImageResponse(image.getS3Url(), image.getSortOrder());
        }
    }
}

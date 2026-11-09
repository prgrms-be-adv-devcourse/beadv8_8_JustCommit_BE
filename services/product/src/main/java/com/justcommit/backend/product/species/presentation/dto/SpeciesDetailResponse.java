package com.justcommit.backend.product.species.presentation.dto;


import java.util.List;
import com.justcommit.backend.product.species.domain.Species;
import com.justcommit.backend.product.species.domain.SpeciesPicture;

public record SpeciesDetailResponse(
        Long speciesId,
        String name,
        String category,
        String light,
        String watering,
        String difficulty,
        String color,
        boolean isBanned,
        List<PictureResponse> pictures
) {
    public static SpeciesDetailResponse from(Species species) {
        return new SpeciesDetailResponse(
                species.getId(),
                species.getName(),
                nameOf(species.getCategory()),
                nameOf(species.getLight()),
                nameOf(species.getWatering()),
                nameOf(species.getDifficulty()),
                nameOf(species.getColor()),
                species.isBanned(),
                species.getPictures().stream()
                        .map(PictureResponse::from)
                        .toList());
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }

    public record PictureResponse(String s3Url, int sortOrder) {

        static PictureResponse from(SpeciesPicture picture) {
            return new PictureResponse(picture.getS3Url(), picture.getSortOrder());
        }
    }
}

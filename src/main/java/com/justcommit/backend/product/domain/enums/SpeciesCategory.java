package com.justcommit.backend.product.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SpeciesCategory implements LabeledEnum{
    FOLIAGE("관엽식물"),
    FOLIAGE_FLOWER("잎·꽃 식물"),
    FLOWER("꽃 식물"),
    FRUIT("열매 식물"),
    SUCCULENT("다육·선인장");

    private final String label;
}

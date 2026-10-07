package com.justcommit.backend.product.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Watering implements LabeledEnum {
    ALWAYS_WET("항상 축축하게"),
    KEEP_MOIST("촉촉하게 유지"),
    WHEN_SURFACE_DRY("겉흙이 마르면"),
    WHEN_MOSTLY_DRY("흙이 대부분 마르면");

    private final String label;
}
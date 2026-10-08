package com.justcommit.backend.product.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Light implements LabeledEnum {
    LOW("낮은 광도"),
    MEDIUM("중간 광도"),
    HIGH("높은 광도");

    private final String label;
}

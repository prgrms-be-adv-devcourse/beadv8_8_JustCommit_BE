package com.justcommit.backend.product.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Difficulty implements LabeledEnum {
    EASY("쉬움"),
    NORMAL("보통"),
    HARD("어려움");

    private final String label;
}
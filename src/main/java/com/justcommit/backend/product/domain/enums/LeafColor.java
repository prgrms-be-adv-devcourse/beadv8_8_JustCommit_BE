package com.justcommit.backend.product.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum LeafColor implements LabeledEnum {
    GREEN("초록"),
    YELLOW("노랑"),
    WHITE("흰색"),
    SILVER("은색"),
    RED("붉은색"),
    MIXED("혼합"),
    ETC("기타");

    private final String label;
}
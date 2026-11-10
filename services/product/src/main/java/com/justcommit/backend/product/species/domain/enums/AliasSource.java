package com.justcommit.backend.product.species.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AliasSource implements LabeledEnum {
    NONGSARO("농사로 유통명"),
    AT_AUCTION("aT 화훼 경매"),
    MANUAL("직접 조사");

    private final String label;
}

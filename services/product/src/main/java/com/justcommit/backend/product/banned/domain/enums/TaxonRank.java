package com.justcommit.backend.product.banned.domain.enums;

// 금지 단위: 속이면 그 속 전체, 종이면 그 종과 아래 변종, 변종이면 그 변종만
public enum TaxonRank {
    GENUS, SPECIES, INFRA
}

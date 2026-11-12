package com.justcommit.backend.product.review.domain;

// 판매자가 고른 품종. other = "기타" 선택 (그러면 학명이 없을 수 있음)
public record SellerSpecies(String scientificName, boolean other) {

    public static SellerSpecies of(String scientificName) {
        return new SellerSpecies(scientificName, false);
    }

    public static SellerSpecies otherSpecies() {
        return new SellerSpecies(null, true);
    }
}

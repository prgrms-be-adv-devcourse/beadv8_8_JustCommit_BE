package com.justcommit.backend.product.review.domain;

// Haiku가 답한 품종 후보 하나 (학명 · 한글 이름 · 확신도 0~1)
public record Candidate(String scientificName, String koreanName, double confidence) {
}

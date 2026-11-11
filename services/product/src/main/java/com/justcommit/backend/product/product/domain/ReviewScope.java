package com.justcommit.backend.product.product.domain;

// 수정한 내용에 따라 다시 할 검수 범위
public enum ReviewScope {
    FULL,       // 사진 · 품종이 바뀜: 처음부터 사진 검수
    TEXT_ONLY   // 글만 바뀜: 글 유해 검사 · 금지어 대조만
}
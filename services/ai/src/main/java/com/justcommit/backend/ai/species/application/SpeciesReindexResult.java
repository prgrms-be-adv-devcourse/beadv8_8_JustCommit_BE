package com.justcommit.backend.ai.species.application;

// 품종 재임베딩 결과: 전체 품종 수, 새로 임베딩한 수, 문서가 그대로라 건너뛴 수, 프로필에 없어 지운 벡터 수
public record SpeciesReindexResult(int total, int embedded, int skipped, int deleted) {
}

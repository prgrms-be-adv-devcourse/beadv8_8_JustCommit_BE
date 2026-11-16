package com.justcommit.backend.ai.species.application;

// 임베딩할 품종 문서와 그 해시 (해시가 같으면 다시 임베딩하지 않음)
public record SpeciesDocument(int speciesId, String name, String text, String hash) {
}

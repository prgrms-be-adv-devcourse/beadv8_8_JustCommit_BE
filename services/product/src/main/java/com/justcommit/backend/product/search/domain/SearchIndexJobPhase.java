package com.justcommit.backend.product.search.domain;

/**
 * 상품 검색 색인 작업 단계 (#563). 전체 색인은 FILLING → COMPLETED만 거친다.
 * COMPLETED · FAILED가 끝 단계이고, 끝나지 않은 작업은 전체에서 하나만 둘 수 있다 (V15 uk_search_index_job_running).
 */
public enum SearchIndexJobPhase {
    // 등록만 하고 아직 실행자가 시작하지 않음
    QUEUED,
    // 새 인덱스(재색인) · alias(전체 색인)에 DB 전체를 채우는 중
    FILLING,
    // 새 인덱스를 refresh하고 alias를 옮기는 중
    SWITCHING_ALIAS,
    // 채우는 동안 옛 인덱스로 간 변경을 맞추는 중 (alias에 전체 색인 한 번 더)
    CATCHING_UP,
    // 옛 인덱스를 지우는 중
    CLEANING_UP,
    COMPLETED,
    FAILED;

    public boolean isFinished() {
        return this == COMPLETED || this == FAILED;
    }
}

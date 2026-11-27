package com.justcommit.backend.product.search.application;

import com.justcommit.backend.product.search.domain.SearchIndexJobPhase;

/**
 * 전체 색인 · 무중단 재색인의 진행을 작업 기록에 남기는 쪽 (#563, 구현: {@link SearchIndexJobTracker.JobProgress}).
 *
 * <p>작업이 이미 끝난 것으로 기록돼 있으면(죽은 작업으로 실패 처리됨) {@link SearchIndexJobAbandonedException}을 던져
 * 늦게 깨어난 실행이 더 쓰지 않고 멈추게 한다.
 */
public interface IndexProgress {

    // 기록 없이 실행 (테스트 · 작업 기록 밖에서 부를 때)
    IndexProgress NONE = new IndexProgress() {
        @Override
        public void phase(SearchIndexJobPhase phase, String previousIndex, String nextIndex) {
        }

        @Override
        public void batch(int processed, int skipped, int failed) {
        }
    };

    // 단계가 바뀔 때. 인덱스 이름은 재색인만 (전체 색인은 null)
    void phase(SearchIndexJobPhase phase, String previousIndex, String nextIndex);

    // bulk 배치 하나를 보낸 뒤: 보낸 수(색인 + 삭제) · 그중 건너뜀 · 실패
    void batch(int processed, int skipped, int failed);
}

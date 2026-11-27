package com.justcommit.backend.product.search.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 실행 서버가 죽어 하트비트가 끊긴 색인 작업을 실패 처리해 잠금을 푼다 (#563, docs/reference/schedulers.md).
// 새 요청도 등록 전에 같은 정리를 하므로, 이 스케줄러는 요청이 없을 때 상태 조회가 계속 「진행 중」으로 남지 않게 하는 용도.
// 스케줄링은 common-messaging-outbox(@EnableScheduling)가 켬. 끄려면 custom.search.index-job.stale-check.enabled=false
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "custom.search.index-job.stale-check.enabled", havingValue = "true", matchIfMissing = true)
public class SearchIndexJobStaleScheduler {

    private final SearchIndexJobService jobService;

    @Scheduled(fixedDelayString = "${custom.search.index-job.stale-check.interval:PT1M}",
            initialDelayString = "${custom.search.index-job.stale-check.interval:PT1M}")
    public void failStaleJobs() {
        jobService.failStaleJobs();
    }
}

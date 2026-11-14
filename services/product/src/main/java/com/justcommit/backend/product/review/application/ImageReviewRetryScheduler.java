package com.justcommit.backend.product.review.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 검수 엔진 오류 재처리 (docs/reference/schedulers.md 「검수 엔진 오류 재처리」)
// 5분마다 ENGINE_ERROR 건을 한 건씩(SKIP LOCKED) 같은 검수 버전으로 다시 검수. 파드가 여러 개여도 같은 건을 겹쳐 집지 않음
// 스케줄링은 common-messaging-outbox(@EnableScheduling)가 켬. 끄려면 custom.review.retry.enabled=false
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "custom.review.retry.enabled", havingValue = "true", matchIfMissing = true)
public class ImageReviewRetryScheduler {

    private final ImageReviewService imageReviewService;

    @Scheduled(fixedDelayString = "${custom.review.retry.interval:PT5M}", initialDelayString = "${custom.review.retry.interval:PT5M}")
    public void retryEngineErrors() {
        // 키가 없으면 엔진 오류는 처음부터 관리자 확인으로 가므로 재처리할 건이 생기지 않음. 헛돌지 않게 건너뜀
        if (!imageReviewService.isEngineConfigured()) {
            return;
        }
        int processed = imageReviewService.retryEngineErrors();
        if (processed > 0) {
            log.info("검수 엔진 오류 재처리 {}건", processed);
        }
    }
}

package com.justcommit.backend.product.review.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 관리자 확인 기한 (docs/reference/schedulers.md 「관리자 확인 지연」)
// 관리자 확인으로 간 지 24시간이 지난 검수를 REVIEW_EXPIRED로 자동 반려. 파드가 여러 개여도 같은 건을 겹쳐 집지 않음(SKIP LOCKED)
// 12시간 관리자 알림은 notification 연동 때 붙임 (지금은 관리자 목록의 「지연」 표시만). 끄려면 custom.review.deadline.enabled=false
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "custom.review.deadline.enabled", havingValue = "true", matchIfMissing = true)
public class ImageReviewDeadlineScheduler {

    private final AdminImageReviewService adminImageReviewService;

    @Scheduled(fixedDelayString = "${custom.review.deadline.interval:PT10M}", initialDelayString = "${custom.review.deadline.interval:PT10M}")
    public void expireOverdue() {
        int processed = adminImageReviewService.expireOverdue();
        if (processed > 0) {
            log.info("관리자 확인 기한 초과 자동 반려 {}건", processed);
        }
    }
}

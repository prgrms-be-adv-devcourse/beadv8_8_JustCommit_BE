package com.justcommit.backend.product.review.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 관리자 확인 기한 (docs/reference/schedulers.md 「관리자 확인 지연」). 관리자 확인으로 간 시각부터 세는 두 작업을 한 곳에 둠
// 1) 10분마다: 24시간이 지난 검수를 REVIEW_EXPIRED로 자동 반려. 파드가 여러 개여도 같은 건을 겹쳐 집지 않음(SKIP LOCKED)
// 2) 한국 시간 09~21시 매 정각: 12시간이 지났고 아직 알리지 않은 건을 묶어 관리자 알림 이벤트 ImageReviewDelayed 1개 발행 (#509, 0건이면 보내지 않음)
//    밤에는 관리자에게 알림을 보내지 않음. 밤사이 늦어진 건은 09시에 한 번에 묶임 (자동 반려는 밤에도 그대로 10분마다)
//    두 작업은 따로 돌기 때문에, 24시간이 지나 반려를 기다리는 건은 조회 조건으로 알림에서 뺌
// 끄려면 custom.review.deadline.enabled=false (두 작업 모두)
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "custom.review.deadline.enabled", havingValue = "true", matchIfMissing = true)
public class ImageReviewDeadlineScheduler {

    private final AdminImageReviewService adminImageReviewService;

    // 반려는 늦어지면 판매자가 그만큼 기다리므로 짧은 주기
    @Scheduled(fixedDelayString = "${custom.review.deadline.interval:PT10M}", initialDelayString = "${custom.review.deadline.interval:PT10M}")
    public void expireOverdue() {
        int processed = adminImageReviewService.expireOverdue();
        if (processed > 0) {
            log.info("관리자 확인 기한 초과 자동 반려 {}건", processed);
        }
    }

    // 관리자 알림은 운영 시간(09~21시)에 매 정각, 그사이 새로 지연된 건을 모아 한 번에 (알림이 너무 잦지 않게)
    // 알림 구간(12~24시간)과 밤 공백(21시~09시)이 모두 12시간이라 어떤 건이든 구간 안에서 한 번은 실행을 만남
    // 실패하면(DB 오류 등) 알림 시각도 롤백돼 다음 실행에서 다시 모음. 시간대는 서비스 Clock(ClockConfig.SEOUL)과 같게
    @Scheduled(cron = "${custom.review.delay-alert.cron:0 0 9-21 * * *}", zone = "Asia/Seoul")
    public void notifyDelayed() {
        adminImageReviewService.notifyDelayed();
    }
}

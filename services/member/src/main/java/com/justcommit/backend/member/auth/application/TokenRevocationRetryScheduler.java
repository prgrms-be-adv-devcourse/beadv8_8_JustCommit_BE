package com.justcommit.backend.member.auth.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 토큰 폐기 재처리 (docs/reference/schedulers.md 「토큰 폐기 재처리」)
// 10초마다 다음 시도 시각이 지난 PENDING 작업을 한 건씩(SKIP LOCKED) 다시 기록. 파드가 여러 개여도 같은 건을 겹쳐 집지 않음
// 스케줄링은 common-messaging-outbox(@EnableScheduling)가 켬. 끄려면 member.token-revocation.retry.enabled=false
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "member.token-revocation.retry.enabled", havingValue = "true", matchIfMissing = true)
public class TokenRevocationRetryScheduler {

  private final TokenRevocationRetryService retryService;

  @Scheduled(fixedDelayString = "${member.token-revocation.retry.interval:PT10S}",
      initialDelayString = "${member.token-revocation.retry.interval:PT10S}")
  public void retryPending() {
    try {
      int processed = retryService.retryPending();
      if (processed > 0) {
        log.info("토큰 폐기 재처리 {}건", processed);
      }
    } catch (RuntimeException e) {
      // DB 장애 등으로 잡기 자체가 실패. 다음 주기에 다시 시도
      log.warn("토큰 폐기 재처리 실패", e);
    }
  }
}

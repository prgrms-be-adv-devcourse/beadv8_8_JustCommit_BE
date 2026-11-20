package com.justcommit.backend.member.unlink.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 카카오 연결 해제 웹훅 처리 (docs/reference/schedulers.md 「카카오 연결 해제 웹훅 처리」)
// 10초마다 처리할 수신 기록을 한 건씩(SKIP LOCKED) 처리. 파드가 여러 개여도 같은 건을 겹쳐 집지 않음
// 끄려면 member.kakao-unlink.processing.enabled=false
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "member.kakao-unlink.processing.enabled", havingValue = "true", matchIfMissing = true)
public class KakaoUnlinkScheduler {

  private final KakaoUnlinkProcessor processor;

  @Scheduled(fixedDelayString = "${member.kakao-unlink.processing.interval:PT10S}",
      initialDelayString = "${member.kakao-unlink.processing.interval:PT10S}")
  public void processDue() {
    try {
      int processed = processor.processDue();
      if (processed > 0) {
        log.info("카카오 연결 해제 웹훅 처리 {}건", processed);
      }
    } catch (RuntimeException e) {
      // DB 장애 등으로 확보 자체가 실패. 다음 주기에 다시 시도
      log.warn("카카오 연결 해제 웹훅 처리 실패: error={}", e.getClass().getSimpleName());
    }
  }
}

package com.justcommit.backend.member.withdrawal.application;

import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import com.justcommit.backend.member.withdrawal.infrastructure.repository.MemberWithdrawalRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

// 탈퇴 기록 정리 (docs/reference/schedulers.md 「탈퇴 기록 정리」, #407)
// 재가입 제한 기간(30일)이 지난 탈퇴 기록을 지운다. 판정은 RejoinRestriction이 DB 시각으로 직접 하므로,
// 이 작업이 늦어도 가입 결과는 바뀌지 않고 해시를 늦게 지울 뿐이다
// 같은 조건의 삭제는 여러 번 돌아도 결과가 같아 중복 실행 방지(잠금)를 두지 않는다
// 스케줄링은 common-messaging-outbox(@EnableScheduling)가 켬. 끄려면 member.withdrawal-record.cleanup.enabled=false
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "member.withdrawal-record.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class WithdrawalRecordCleanupScheduler {

  private final MemberWithdrawalRecordRepository recordRepository;
  private final DatabaseClock databaseClock;

  // 시작 직후 한 번(서버가 꺼져 있던 동안 기간이 끝난 기록), 그 뒤로는 끝난 시점부터 interval마다
  @Scheduled(fixedDelayString = "${member.withdrawal-record.cleanup.interval:PT1H}", initialDelayString = "PT0S")
  public void cleanup() {
    try {
      Instant before = databaseClock.now().minus(RejoinRestriction.PERIOD);
      int deleted = recordRepository.deleteWithdrawnAtOrBefore(before);
      if (deleted > 0) {
        log.info("탈퇴 기록 정리 {}건", deleted);
      }
    } catch (RuntimeException e) {
      // DB 장애 등. 다음 주기에 다시 시도
      log.warn("탈퇴 기록 정리 실패", e);
    }
  }
}

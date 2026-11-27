package com.justcommit.backend.member.withdrawal.application;

import com.justcommit.backend.member.support.MemberIntegrationTest;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import com.justcommit.backend.member.withdrawal.domain.MemberWithdrawalRecord;
import com.justcommit.backend.member.withdrawal.infrastructure.repository.MemberWithdrawalRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 탈퇴 기록 정리 (#407)를 실제 PostgreSQL로 확인한다.
 * - 테스트 프로필은 스케줄러를 끄므로(빈 없음) 스케줄러를 직접 만들어 호출한다
 * - 이 클래스에는 @Transactional이 없어, 벌크 DELETE 뒤의 조회는 DB에서 다시 읽은 값이다
 */
class WithdrawalRecordCleanupSchedulerIntegrationTest extends MemberIntegrationTest {

  @Autowired
  private MemberWithdrawalRecordRepository recordRepository;

  @Autowired
  private DatabaseClock databaseClock;

  @Test
  @DisplayName("기준 시각과 같거나 이전에 탈퇴한 기록만 지운다 (재가입 제한이 끝난 경계 포함)")
  void deleteBoundary() {
    // DB 시각이 흐르지 않도록 고정 시각으로 리포지토리 조건만 확인
    Instant before = Instant.parse("2026-01-01T00:00:00Z");
    Long earlier = save(before.minusSeconds(1));
    Long exact = save(before);
    Long later = save(before.plusSeconds(1));

    int deleted = recordRepository.deleteWithdrawnAtOrBefore(before);

    assertThat(deleted).isEqualTo(2);
    assertThat(recordRepository.existsById(earlier)).isFalse();
    assertThat(recordRepository.existsById(exact)).isFalse();
    assertThat(recordRepository.existsById(later)).isTrue();
  }

  @Test
  @DisplayName("30일이 지난 기록은 지우고, 아직 30일이 안 된 기록은 남긴다")
  void cleanupExpiredRecords() {
    Instant now = databaseClock.now();
    Long expired = save(now.minus(RejoinRestriction.PERIOD).minus(Duration.ofMinutes(1)));
    Long active = save(now.minus(RejoinRestriction.PERIOD).plus(Duration.ofMinutes(1)));

    new WithdrawalRecordCleanupScheduler(recordRepository, databaseClock).cleanup();

    assertThat(recordRepository.existsById(expired)).isFalse();
    assertThat(recordRepository.existsById(active)).isTrue();
  }

  private Long save(Instant withdrawnAt) {
    return recordRepository.save(MemberWithdrawalRecord.create("a".repeat(64), "b".repeat(64), withdrawnAt)).getId();
  }
}

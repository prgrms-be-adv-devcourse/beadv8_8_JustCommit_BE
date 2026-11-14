package com.justcommit.backend.member.auth.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 회원 토큰 폐기 작업 (#409). 비밀번호 변경 · 탈퇴와 같은 트랜잭션에서 저장한다.
 * - cutoffAt: 폐기 시각(초 단위 버림). Gateway는 iat <= cutoffAt인 Access를 거절한다
 * - retainUntil: Redis 폐기 키를 유지해야 하는 기한 (cutoffAt + Access 수명 + 여유). 지나면 기록할 필요가 없다
 * - tokenVersion: 올린 뒤의 인증 버전. 이보다 작은 버전의 Refresh를 정리한다
 */
@Entity
@Table(name = "member_token_revocation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TokenRevocation extends BaseTimeEntity {

  private static final int ERROR_MAX_LENGTH = 500;

  @Column(name = "member_id", nullable = false)
  private Long memberId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private RevocationReason reason;

  @Column(name = "token_version", nullable = false)
  private int tokenVersion;

  @Column(name = "cutoff_at", nullable = false)
  private Instant cutoffAt;

  @Column(name = "retain_until", nullable = false)
  private Instant retainUntil;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private RevocationStatus status;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at")
  private Instant nextAttemptAt;

  @Column(name = "last_error", length = ERROR_MAX_LENGTH)
  private String lastError;

  // cutoffAt은 초 단위로 버린다 (Access의 iat와 같은 단위로 비교하기 위해)
  // nextAttemptAt을 cutoffAt으로 두어, 커밋 직후 기록 전에 서버가 죽어도 재처리 스케줄러가 집을 수 있게 한다
  public static TokenRevocation create(Long memberId, RevocationReason reason, int tokenVersion,
                                        Instant now, Duration retention) {
    TokenRevocation revocation = new TokenRevocation();
    Instant cutoffAt = now.truncatedTo(ChronoUnit.SECONDS);
    revocation.memberId = memberId;
    revocation.reason = reason;
    revocation.tokenVersion = tokenVersion;
    revocation.cutoffAt = cutoffAt;
    revocation.retainUntil = cutoffAt.plus(retention);
    revocation.status = RevocationStatus.PENDING;
    revocation.attemptCount = 0;
    revocation.nextAttemptAt = cutoffAt;
    return revocation;
  }

  public void markRecorded() {
    this.status = RevocationStatus.RECORDED;
    this.nextAttemptAt = null;
    this.lastError = null;
  }

  public void markExpired() {
    this.status = RevocationStatus.EXPIRED;
    this.nextAttemptAt = null;
  }

  // Redis 기록 실패: 횟수 +1, 다음 시도 시각과 원인을 남긴다 (PENDING 유지)
  public void recordFailure(String error, Instant nextAttemptAt) {
    this.attemptCount++;
    this.nextAttemptAt = nextAttemptAt;
    this.lastError = (error == null || error.length() <= ERROR_MAX_LENGTH)
        ? error
        : error.substring(0, ERROR_MAX_LENGTH);
  }
}

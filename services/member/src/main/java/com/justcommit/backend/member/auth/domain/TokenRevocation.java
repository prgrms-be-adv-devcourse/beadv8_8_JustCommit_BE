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

  // 최초 기록의 임대 시간: 커밋 직후 첫 기록 도중 앱이 죽으면 이 시간이 지난 뒤 재처리 스케줄러가 집는다
  public static final Duration FIRST_RECORD_GRACE = Duration.ofSeconds(30);

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
  // nextAttemptAt은 최초 기록의 임대 기한: 첫 기록이 끝나기 전에는 재처리가 집지 않고, 앱이 죽으면 이 시각 뒤에 집힌다
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
    revocation.nextAttemptAt = cutoffAt.plus(FIRST_RECORD_GRACE);
    return revocation;
  }

  // 재처리 임대: 이 시각까지 다른 실행이 집지 않는다. 처리 중 앱이 죽어도 이 시각이 지나면 다시 집힌다
  // 이 값은 실패 UPDATE의 조건으로도 쓴다 (늦게 끝난 이전 실행이 새 임대를 덮어쓰지 못하게)
  public void lease(Instant until) {
    this.nextAttemptAt = until;
  }
}

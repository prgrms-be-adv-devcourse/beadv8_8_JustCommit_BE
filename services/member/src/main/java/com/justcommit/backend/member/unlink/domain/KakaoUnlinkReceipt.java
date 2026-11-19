package com.justcommit.backend.member.unlink.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 카카오 연결 해제 웹훅 수신 기록 (#422). 요청마다 한 행을 남기고, 스케줄러가 단계(status)를 따라 처리한다.
 * - 칼럼 뜻 · 제약 → Flyway V10, 처리 규칙 → docs/flows/signup-and-replica.md 「카카오 연결 해제 웹훅」
 * - 시각은 DB 시각(DatabaseClock)
 */
@Entity
@Table(name = "member_kakao_unlink_receipt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KakaoUnlinkReceipt extends BaseTimeEntity {

  public static final int PROVIDER_ID_MAX_LENGTH = 20;
  public static final int REFERRER_TYPE_MAX_LENGTH = 40;
  public static final int ERROR_MAX_LENGTH = 50;
  // 단계별 최초 포함 총 5회. 1 ~ 4번째 실패 뒤 기다리는 시간, 5번째 실패에 FAILED
  public static final int MAX_FAILURES = 5;
  private static final Duration[] RETRY_DELAYS = {
      Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(120)};

  @Column(name = "provider_id", length = PROVIDER_ID_MAX_LENGTH)
  private String providerId;

  @Column(name = "referrer_type", length = REFERRER_TYPE_MAX_LENGTH)
  private String referrerType;

  @Column(name = "received_at", nullable = false)
  private Instant receivedAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private KakaoUnlinkStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "resume_state", length = 20)
  private KakaoUnlinkStatus resumeState;

  @Column(name = "member_id")
  private Long memberId;

  @Column(name = "unlink_version")
  private Integer unlinkVersion;

  @Column(name = "handles_withdrawal", nullable = false)
  private boolean handlesWithdrawal;

  @Column(name = "attempt_no", nullable = false)
  private int attemptNo;

  @Column(name = "lease_until")
  private Instant leaseUntil;

  @Column(name = "failure_count", nullable = false)
  private int failureCount;

  @Column(name = "next_attempt_at")
  private Instant nextAttemptAt;

  @Column(name = "last_error", length = ERROR_MAX_LENGTH)
  private String lastError;

  // 정상 요청: 처리 대기(PENDING), 바로 처리 대상
  public static KakaoUnlinkReceipt received(String providerId, String referrerType, Instant now) {
    KakaoUnlinkReceipt receipt = base(Objects.requireNonNull(providerId, "providerId"), referrerType, now);
    receipt.status = KakaoUnlinkStatus.PENDING;
    receipt.nextAttemptAt = now;
    return receipt;
  }

  // 회원 처리 없이 기록만 남기는 요청(잘못된 값 · 처리 대상 아님). errorCode는 정해진 코드만 (원문 · 예외 메시지 금지)
  public static KakaoUnlinkReceipt ignored(String providerId, String referrerType, String errorCode, Instant now) {
    KakaoUnlinkReceipt receipt = base(providerId, referrerType, now);
    receipt.status = KakaoUnlinkStatus.IGNORED;
    receipt.lastError = checkLength(errorCode, ERROR_MAX_LENGTH, "errorCode");
    return receipt;
  }

  // ===== 처리 (#422). 모든 변경은 기록 행을 잠근 트랜잭션에서, 처리권과 단계를 확인한 뒤에만 부른다 =====

  // 확보: 처리권을 올려 이전 작업자의 결과 반영을 막고, 임대 시각까지 다른 실행이 집지 않게 한다
  public void claim(Instant leaseUntil) {
    this.attemptNo++;
    this.leaseUntil = leaseUntil;
  }

  // 확보한 처리권과 단계가 그대로인지 (임대 시각은 보지 않음: 다른 작업자가 가져갔다면 처리권이 바뀌어 있음)
  public boolean isClaimedBy(int attemptNo, KakaoUnlinkStatus expected) {
    return this.attemptNo == attemptNo && this.status == expected;
  }

  public void ignore(String errorCode) {
    finish(KakaoUnlinkStatus.IGNORED);
    this.lastError = checkLength(errorCode, ERROR_MAX_LENGTH, "errorCode");
  }

  public void invalidate() {
    finish(KakaoUnlinkStatus.INVALIDATED);
  }

  // INACTIVE + 토큰 폐기가 반영된 연결 해제 상태에 연결하고 다음 단계로 (단계가 넘어가면 실패 횟수 0)
  public void markInactivated(Long memberId, int unlinkVersion, Instant now) {
    this.status = KakaoUnlinkStatus.INACTIVATED;
    this.memberId = Objects.requireNonNull(memberId, "memberId");
    this.unlinkVersion = unlinkVersion;
    this.failureCount = 0;
    this.lastError = null;
    this.leaseUntil = null;
    this.nextAttemptAt = now;
  }

  // 현재 단계 실패: 다음 시도를 미루고, 5번째 실패면 재개할 단계를 남기고 FAILED (운영자 재개)
  public void recordFailure(String errorCode, Instant now) {
    this.failureCount++;
    this.lastError = checkLength(errorCode, ERROR_MAX_LENGTH, "errorCode");
    this.leaseUntil = null;
    if (this.failureCount >= MAX_FAILURES) {
      this.resumeState = this.status;
      this.status = KakaoUnlinkStatus.FAILED;
      this.nextAttemptAt = null;
    } else {
      this.nextAttemptAt = now.plus(RETRY_DELAYS[this.failureCount - 1]);
    }
  }

  private void finish(KakaoUnlinkStatus finalStatus) {
    this.status = finalStatus;
    this.resumeState = null;
    this.leaseUntil = null;
    this.nextAttemptAt = null;
  }

  private static KakaoUnlinkReceipt base(String providerId, String referrerType, Instant now) {
    KakaoUnlinkReceipt receipt = new KakaoUnlinkReceipt();
    receipt.providerId = checkLength(providerId, PROVIDER_ID_MAX_LENGTH, "providerId");
    receipt.referrerType = checkLength(referrerType, REFERRER_TYPE_MAX_LENGTH, "referrerType");
    receipt.receivedAt = Objects.requireNonNull(now, "now");
    return receipt;
  }

  // 길이 제한은 받는 쪽(컨트롤러)에서 먼저 거른다. 여기까지 왔다면 프로그래밍 오류
  private static String checkLength(String value, int max, String name) {
    if (value != null && value.length() > max) {
      throw new IllegalArgumentException(name + " 길이 초과: " + value.length());
    }
    return value;
  }
}

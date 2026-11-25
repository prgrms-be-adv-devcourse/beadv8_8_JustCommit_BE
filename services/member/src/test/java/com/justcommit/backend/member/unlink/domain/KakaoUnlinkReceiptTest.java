package com.justcommit.backend.member.unlink.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

// 탈퇴 보류로 정해진 수신 기록인지 (#616): 재로그인 복구 때 팝업 안내 대상을 가른다
class KakaoUnlinkReceiptTest {

  private static final Instant NOW = Instant.parse("2026-11-25T01:00:00Z");

  @Test
  @DisplayName("자동 탈퇴 처리 기록이 BLOCKED로 안내 대기(NOTICE_PENDING)면 탈퇴 보류")
  void noticePendingIsHeld() {
    KakaoUnlinkReceipt receipt = handler();
    receipt.markNoticePending(NOW);

    assertThat(receipt.isWithdrawalHeld()).isTrue();
  }

  @Test
  @DisplayName("안내 메일을 보내고 끝난(DONE) 처리 기록도 탈퇴 보류")
  void noticeDoneIsHeld() {
    KakaoUnlinkReceipt receipt = handler();
    receipt.markNoticePending(NOW);
    receipt.markDone();

    assertThat(receipt.isWithdrawalHeld()).isTrue();
  }

  @Test
  @DisplayName("안내 메일 단계에서 재시도를 다 써 멈춘(FAILED) 처리 기록도 탈퇴 보류")
  void failedAtNoticeIsHeld() {
    KakaoUnlinkReceipt receipt = handler();
    receipt.markNoticePending(NOW);
    failUntilStopped(receipt);

    assertThat(receipt.getStatus()).isEqualTo(KakaoUnlinkStatus.FAILED);
    assertThat(receipt.isWithdrawalHeld()).isTrue();
  }

  @Test
  @DisplayName("탈퇴 확인 전(INACTIVATED)이거나 확인 단계에서 멈췄으면 아직 보류인지 모름")
  void beforeDecisionIsNotHeld() {
    KakaoUnlinkReceipt waiting = handler();
    KakaoUnlinkReceipt stopped = handler();
    failUntilStopped(stopped);

    assertThat(waiting.isWithdrawalHeld()).isFalse();
    assertThat(stopped.getStatus()).isEqualTo(KakaoUnlinkStatus.FAILED);
    assertThat(stopped.isWithdrawalHeld()).isFalse();
  }

  @Test
  @DisplayName("자동 탈퇴 처리 기록이 아니면(관리자 해제 · 비대상 사유로 DONE) 보류가 아님")
  void doneWithoutHandlingWithdrawalIsNotHeld() {
    KakaoUnlinkReceipt receipt = KakaoUnlinkReceipt.received("123", "UNLINK_FROM_ADMIN", NOW);
    receipt.markInactivated(4L, 1, NOW);
    receipt.markDone();

    assertThat(receipt.isWithdrawalHeld()).isFalse();
  }

  @Test
  @DisplayName("재로그인 · 재연결로 무효가 된 기록은 보류가 아님")
  void invalidatedIsNotHeld() {
    KakaoUnlinkReceipt receipt = handler();
    receipt.markNoticePending(NOW);
    receipt.invalidate();

    assertThat(receipt.isWithdrawalHeld()).isFalse();
  }

  // INACTIVE 반영 뒤 자동 탈퇴 처리 기록으로 지정된 기록
  private static KakaoUnlinkReceipt handler() {
    KakaoUnlinkReceipt receipt = KakaoUnlinkReceipt.received("123", "UNLINK_FROM_APPS", NOW);
    receipt.markInactivated(4L, 1, NOW);
    receipt.markHandlesWithdrawal();
    return receipt;
  }

  private static void failUntilStopped(KakaoUnlinkReceipt receipt) {
    for (int i = 0; i < KakaoUnlinkReceipt.MAX_FAILURES; i++) {
      receipt.recordFailure("TEST_FAILURE", NOW);
    }
  }
}

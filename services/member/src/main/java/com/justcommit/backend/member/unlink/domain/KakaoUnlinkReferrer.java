package com.justcommit.backend.member.unlink.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * 카카오 연결 해제 웹훅의 사유(referrer_type) (#422). 수신 · 처리가 같은 목록을 쓴다.
 * 요청 · DB에는 문자열 그대로 두고(모르는 사유도 기록해야 하므로), 판단할 때만 이 값으로 읽는다.
 */
public enum KakaoUnlinkReferrer {

  ACCOUNT_DELETE(true),
  FORCED_ACCOUNT_DELETE(true),
  UNLINK_FROM_APPS(true),
  // 관리자 해제: 사용자 의사를 알 수 없어 INACTIVE까지만
  UNLINK_FROM_ADMIN(false),
  // 가입 미완료: 기록만 (카카오 조회 없이 끝냄)
  INCOMPLETE_SIGN_UP(false);

  private final boolean autoWithdrawal;

  KakaoUnlinkReferrer(boolean autoWithdrawal) {
    this.autoWithdrawal = autoWithdrawal;
  }

  // 자동 탈퇴 대상 사유인지
  public boolean isAutoWithdrawal() {
    return autoWithdrawal;
  }

  // 저장된 문자열이 이 사유인지 (null · 모르는 값이면 false)
  public boolean matches(String value) {
    return name().equals(value);
  }

  // 문자열 → 사유. 없거나 모르는 값은 예외 없이 비어 있음 (valueOf는 모르는 값에 예외를 던져 쓰지 않는다)
  public static Optional<KakaoUnlinkReferrer> from(String value) {
    if (value == null) {
      return Optional.empty();
    }
    return Arrays.stream(values()).filter(referrer -> referrer.name().equals(value)).findFirst();
  }
}

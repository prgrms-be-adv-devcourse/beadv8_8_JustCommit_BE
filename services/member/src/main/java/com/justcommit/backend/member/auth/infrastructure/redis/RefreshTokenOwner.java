package com.justcommit.backend.member.auth.infrastructure.redis;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Refresh 토큰 Redis 값의 주인. 값 형식: "{memberId}:{인증 버전}" (#409)
 * 읽기 규칙: 콜론 기준 정확히 두 부분, 둘 다 숫자만, memberId는 양수 long, 인증 버전은 0 이상 int.
 * 규칙에 맞지 않는 값(콜론 없는 옛 형식, 범위를 넘는 숫자 포함)은 예외 없이 "없음"으로 본다.
 */
public record RefreshTokenOwner(long memberId, int tokenVersion) {

  private static final String SEPARATOR = ":";
  private static final Pattern DIGITS = Pattern.compile("[0-9]+");

  public RefreshTokenOwner {
    if (memberId <= 0 || tokenVersion < 0) {
      throw new IllegalArgumentException("memberId는 양수, 인증 버전은 0 이상이어야 합니다.");
    }
  }

  // Redis에 저장할 값
  public String toValue() {
    return memberId + SEPARATOR + tokenVersion;
  }

  // Redis에서 읽은 값 해석. 규칙에 맞지 않으면 빈 값
  public static Optional<RefreshTokenOwner> parse(String value) {
    if (value == null) {
      return Optional.empty();
    }
    // -1: 뒤쪽 빈 문자열도 보존 ("4:0:" → 세 부분이라 거절)
    String[] parts = value.split(SEPARATOR, -1);
    if (parts.length != 2 || !DIGITS.matcher(parts[0]).matches() || !DIGITS.matcher(parts[1]).matches()) {
      return Optional.empty();
    }
    try {
      long memberId = Long.parseLong(parts[0]);
      int tokenVersion = Integer.parseInt(parts[1]);
      if (memberId <= 0) {
        return Optional.empty();
      }
      return Optional.of(new RefreshTokenOwner(memberId, tokenVersion));
    } catch (NumberFormatException e) {
      // 범위를 넘는 숫자
      return Optional.empty();
    }
  }
}

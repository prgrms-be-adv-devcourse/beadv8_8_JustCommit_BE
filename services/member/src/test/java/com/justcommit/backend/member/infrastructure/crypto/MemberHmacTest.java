package com.justcommit.backend.member.infrastructure.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemberHmacTest {

  // 테스트용 32바이트 키 (값 0으로 채움, 운영 키와 무관)
  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private final MemberHmac hmac = new MemberHmac(KEY);

  @Test
  @DisplayName("정해진 키 · 입력이면 정해진 값 (HMAC-SHA256 · account: 접두사 · UTF-8 · 소문자 hex)")
  void accountHash_knownValue() {
    assertThat(hmac.accountHash("004", "12345678901"))
        .isEqualTo("8a4f47264687ebd14fb1c15df8dfc55438a4d34d1bcc1ac617ac72b6222753ed");
  }

  @Test
  @DisplayName("같은 은행 · 계좌번호는 항상 같은 값")
  void accountHash_sameInput() {
    assertThat(hmac.accountHash("004", "12345678901")).isEqualTo(hmac.accountHash("004", "12345678901"));
  }

  @Test
  @DisplayName("계좌번호가 같아도 은행이 다르면 다른 값")
  void accountHash_differentBank() {
    assertThat(hmac.accountHash("004", "12345678901")).isNotEqualTo(hmac.accountHash("088", "12345678901"));
  }

  @Test
  @DisplayName("앞자리 0은 그대로 반영 (01234567890과 1234567890은 다른 계좌)")
  void accountHash_keepsLeadingZero() {
    assertThat(hmac.accountHash("004", "01234567890")).isNotEqualTo(hmac.accountHash("004", "1234567890"));
  }

  @Test
  @DisplayName("키가 Base64가 아니면 생성 실패")
  void invalidBase64Key() {
    assertThatThrownBy(() -> new MemberHmac("not-base64!!"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("키가 32바이트가 아니면 생성 실패")
  void wrongKeyLength() {
    String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

    assertThatThrownBy(() -> new MemberHmac(shortKey))
        .isInstanceOf(IllegalStateException.class);
  }
}

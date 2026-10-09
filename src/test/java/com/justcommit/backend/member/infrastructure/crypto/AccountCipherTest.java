package com.justcommit.backend.member.infrastructure.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountCipherTest {

  // 테스트용 32바이트 키 (값 0으로 채움, 운영 키와 무관)
  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
  private static final String ACCOUNT_NO = "12345678901234";

  private final AccountCipher cipher = new AccountCipher(KEY);

  @Test
  @DisplayName("암호화한 값을 복호화하면 원래 계좌번호가 나옴")
  void encryptAndDecrypt() {
    String encrypted = cipher.encrypt(ACCOUNT_NO);

    assertThat(cipher.decrypt(encrypted)).isEqualTo(ACCOUNT_NO);
  }

  @Test
  @DisplayName("같은 계좌번호도 암호화할 때마다 다른 암호문이 나오고 원문이 드러나지 않음")
  void encrypt_differentEachTime() {
    String first = cipher.encrypt(ACCOUNT_NO);
    String second = cipher.encrypt(ACCOUNT_NO);

    assertThat(first).isNotEqualTo(second); // IV가 매번 다름
    assertThat(first).startsWith("v1:");
    assertThat(first).doesNotContain(ACCOUNT_NO);
  }

  @Test
  @DisplayName("암호문이 위변조되면 복호화에 실패(GCM 인증 태그)")
  void decrypt_tampered() {
    String encrypted = cipher.encrypt(ACCOUNT_NO);
    String tampered = flipLastByte(encrypted);

    assertThatThrownBy(() -> cipher.decrypt(tampered))
            .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("다른 키로는 복호화 불가")
  void decrypt_withOtherKey() {
    byte[] otherKeyBytes = new byte[32];
    otherKeyBytes[0] = 1;
    AccountCipher otherCipher = new AccountCipher(Base64.getEncoder().encodeToString(otherKeyBytes));

    String encrypted = cipher.encrypt(ACCOUNT_NO);

    assertThatThrownBy(() -> otherCipher.decrypt(encrypted))
            .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("버전 접두사가 없는 값은 복호화하지 않음")
  void decrypt_withoutPrefix() {
    assertThatThrownBy(() -> cipher.decrypt("not-encrypted"))
            .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("키가 32바이트가 아니면 생성할 수 없음")
  void constructor_wrongKeyLength() {
    String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

    assertThatThrownBy(() -> new AccountCipher(shortKey))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("32바이트");
  }

  @Test
  @DisplayName("키가 Base64 형식이 아니면 생성할 수 없음")
  void constructor_notBase64() {
    assertThatThrownBy(() -> new AccountCipher("!!!not-base64!!!"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Base64");
  }

  // 암호문의 마지막 바이트(인증 태그 일부)를 바꿔서 위변조를 흉내
  private String flipLastByte(String encrypted) {
    byte[] bytes = Base64.getDecoder().decode(encrypted.substring("v1:".length()));
    bytes[bytes.length - 1] ^= 1;
    return "v1:" + Base64.getEncoder().encodeToString(bytes);
  }
}

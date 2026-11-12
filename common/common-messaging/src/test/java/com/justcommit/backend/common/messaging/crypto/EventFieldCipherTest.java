package com.justcommit.backend.common.messaging.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventFieldCipherTest {

  private static final String ADDRESS = "서울시 강남구 테헤란로 1, 받는 사람 홍길동 01012345678";
  private static final int IV_BYTES = 12;
  // 실행할 때마다 새로 만들어 레포에 키 값이 남지 않게 한다
  private static final String K1 = newKey();
  private static final String K2 = newKey();

  @Test
  @DisplayName("한글 주소와 빈 문자열을 암호화했다가 그대로 푼다")
  void roundTrip() {
    EventFieldCipher cipher = new EventFieldCipher("k1", Map.of("k1", K1));

    assertThat(cipher.decrypt(cipher.encrypt(ADDRESS))).isEqualTo(ADDRESS);
    assertThat(cipher.decrypt(cipher.encrypt(""))).isEmpty();
  }

  @Test
  @DisplayName("같은 평문도 매번 다른 암호문이 나오고, 접두사는 v1.{현재 키 버전}:이다")
  void differentEachTimeWithPrefix() {
    EventFieldCipher cipher = new EventFieldCipher("k1", Map.of("k1", K1));

    String first = cipher.encrypt(ADDRESS);
    String second = cipher.encrypt(ADDRESS);

    assertThat(first).isNotEqualTo(second).startsWith("v1.k1:");
    assertThat(second).startsWith("v1.k1:");
  }

  @Test
  @DisplayName("키를 바꾼 뒤에도 옛 키 암호문을 풀고, 새 암호문은 실제로 새 키로 만든다")
  void decryptsOldKeyAfterRotation() {
    String old = new EventFieldCipher("k1", Map.of("k1", K1)).encrypt(ADDRESS);
    EventFieldCipher rotated = new EventFieldCipher("k2", Map.of("k1", K1, "k2", K2));

    assertThat(rotated.decrypt(old)).isEqualTo(ADDRESS);

    String newlyEncrypted = rotated.encrypt(ADDRESS);
    assertThat(newlyEncrypted).startsWith("v1.k2:");
    // k2만 가진 쪽에서도 풀리면 접두사만이 아니라 실제로 k2로 암호화된 것
    EventFieldCipher k2Only = new EventFieldCipher("k2", Map.of("k2", K2));
    assertThat(k2Only.decrypt(newlyEncrypted)).isEqualTo(ADDRESS);
  }

  @Test
  @DisplayName("같은 키 바이트를 쓰는 다른 키 버전으로 접두사만 바꾸면 AAD 검증으로 풀리지 않는다")
  void rejectsPrefixTamperingWithSameKeyBytes() {
    // k1 · k2가 같은 키라 키 차이로는 실패하지 않는다 → 실패하면 접두사(AAD) 인증 때문
    EventFieldCipher cipher = new EventFieldCipher("k1", Map.of("k1", K1, "k2", K1));
    String encrypted = cipher.encrypt(ADDRESS);
    String prefixChanged = encrypted.replaceFirst("^v1\\.k1:", "v1.k2:");

    assertThat(prefixChanged).isNotEqualTo(encrypted);
    assertThat(cipher.decrypt(encrypted)).isEqualTo(ADDRESS);
    assertThatThrownBy(() -> cipher.decrypt(prefixChanged))
        .isInstanceOf(EventFieldCipherException.class)
        .hasMessageContaining("위변조")
        .hasNoCause();
  }

  @Test
  @DisplayName("IV · 암호문 · 태그 중 어느 한 바이트를 바꿔도 풀리지 않는다")
  void rejectsBodyTampering() {
    EventFieldCipher cipher = new EventFieldCipher("k1", Map.of("k1", K1));
    String encrypted = cipher.encrypt(ADDRESS);
    int lastIndex = Base64.getDecoder().decode(encrypted.substring(encrypted.indexOf(':') + 1)).length - 1;
    // 0 = IV 첫 바이트, IV_BYTES = 암호문 첫 바이트, lastIndex = 태그 마지막 바이트
    int[] positions = {0, IV_BYTES, lastIndex};

    for (int position : positions) {
      String tampered = flipByte(encrypted, position);
      assertThatThrownBy(() -> cipher.decrypt(tampered))
          .as("바꾼 위치 " + position)
          .isInstanceOf(EventFieldCipherException.class)
          .hasNoCause();
    }
  }

  @Test
  @DisplayName("모르는 키 버전 · 모르는 형식 버전 · 형식이 아닌 값은 풀지 않는다")
  void rejectsUnknownOrMalformed() {
    EventFieldCipher cipher = new EventFieldCipher("k1", Map.of("k1", K1));
    String body = cipher.encrypt(ADDRESS).substring("v1.k1:".length());
    String[] invalidValues = {
        "v1.k9:" + body,   // 모르는 키 버전
        "v2.k1:" + body,   // 모르는 형식 버전
        "abc",             // 구분자 없음
        "v1k1" + body,     // 점 · 콜론 없음
        "v1.k1:@@@",       // Base64 아님
        "v1.k1:AAAA"       // IV + 태그보다 짧음
    };

    for (String value : invalidValues) {
      assertThatThrownBy(() -> cipher.decrypt(value))
          .as(value)
          .isInstanceOf(EventFieldCipherException.class)
          .hasNoCause();
    }
    assertThatThrownBy(() -> cipher.decrypt(null)).isInstanceOf(EventFieldCipherException.class);
  }

  @Test
  @DisplayName("키 설정이 잘못되면 만들 때 막고, 메시지에 키 값을 넣지 않는다")
  void rejectsInvalidKeySettings() {
    String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
    String notBase64 = "not-a-base64-key!!";

    assertInvalid("k1", Map.of());                                       // 키 목록 비었음
    assertInvalid("k2", Map.of("k1", K1));                               // 현재 키가 목록에 없음
    assertInvalid("k1", Map.of("k1", shortKey));                         // 디코딩 16바이트
    assertInvalid("k1", Map.of("k1", notBase64));                        // Base64 아님
    assertInvalid("k1", Map.of("k1", "${MEMBER_ADDRESS_EVENT_KEY}"));    // 환경변수 비었음
    assertInvalid("k1", Map.of("k1", " "));                              // 공백
    assertInvalid("K1", Map.of("K1", K1));                               // 키 버전에 대문자
    assertInvalid("k.1", Map.of("k.1", K1));                             // 키 버전에 구분자

    assertThatThrownBy(() -> new EventFieldCipher("k1", Map.of("k1", notBase64)))
        .hasMessageNotContaining(notBase64)
        .hasNoCause();
    assertThatThrownBy(() -> new EventFieldCipher("k1", Map.of("k1", shortKey)))
        .hasMessageNotContaining(shortKey)
        .hasNoCause();
  }

  @Test
  @DisplayName("복호화 실패 메시지와 원인에 평문 · 암호문이 남지 않는다")
  void failureDoesNotLeak() {
    EventFieldCipher cipher = new EventFieldCipher("k1", Map.of("k1", K1, "k2", K1));
    String tampered = cipher.encrypt(ADDRESS).replaceFirst("^v1\\.k1:", "v1.k2:");

    assertThatThrownBy(() -> cipher.decrypt(tampered))
        .hasMessageNotContaining(ADDRESS)
        .hasMessageNotContaining(tampered.substring(tampered.indexOf(':') + 1))
        .hasNoCause();
  }

  @Test
  @DisplayName("null은 암호화하지 않는다")
  void rejectsNullPlaintext() {
    EventFieldCipher cipher = new EventFieldCipher("k1", Map.of("k1", K1));

    assertThatThrownBy(() -> cipher.encrypt(null)).isInstanceOf(NullPointerException.class);
  }

  private static void assertInvalid(String currentKeyVersion, Map<String, String> keys) {
    assertThatThrownBy(() -> new EventFieldCipher(currentKeyVersion, keys))
        .isInstanceOf(IllegalArgumentException.class)
        .hasNoCause();
  }

  // 접두사는 그대로 두고 Base64 본문의 한 바이트만 바꾼다
  private static String flipByte(String encrypted, int position) {
    int colonIndex = encrypted.indexOf(':');
    byte[] encryptedBytes = Base64.getDecoder().decode(encrypted.substring(colonIndex + 1));
    encryptedBytes[position] ^= 1;
    return encrypted.substring(0, colonIndex + 1) + Base64.getEncoder().encodeToString(encryptedBytes);
  }

  private static String newKey() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }
}

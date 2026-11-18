package com.justcommit.backend.common.messaging.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 이벤트 payload의 민감 필드를 토픽 전송 키로 암호화 · 복호화한다 (AES-256-GCM).
 * 형식: v1.{키 버전}:Base64(IV 12바이트 + 암호문 + 태그 16바이트)
 * - 접두사(v1.{키 버전})를 AAD로 넣어 형식 · 키 버전까지 위변조를 검증한다.
 * - 암호화는 현재 키로만, 복호화는 접두사의 키 버전으로 키를 찾는다 (키 교체 후에도 옛 암호문을 풀 수 있게).
 * - 각 서비스가 @Bean으로 등록해 앱 시작 때 생성자에서 키 설정을 검증한다 (지연 생성 금지).
 * - 예외 메시지에는 평문 · 키 · 암호문을 넣지 않고 원인 예외도 붙이지 않는다.
 */
public final class EventFieldCipher {

  static final String FORMAT_VERSION = "v1";
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int KEY_BYTES = 32;
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final int TAG_BYTES = TAG_BITS / 8;
  // 접두사 "v1.k1:"의 구분자(. :)가 섞이지 않게 키 버전은 영문 소문자, 숫자만
  private static final Pattern KEY_VERSION = Pattern.compile("[a-z0-9]+");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final String currentKeyVersion;
  private final Map<String, SecretKey> keys;

  public EventFieldCipher(String currentKeyVersion, Map<String, String> base64Keys) {
    if (base64Keys == null || base64Keys.isEmpty()) {
      throw new IllegalArgumentException("이벤트 암호화 키 목록이 비어 있습니다.");
    }

    requireKeyVersion(currentKeyVersion);
    Map<String, SecretKey> parsed = new HashMap<>();
    for (Map.Entry<String, String> entry : base64Keys.entrySet()) {
      requireKeyVersion(entry.getKey());
      parsed.put(entry.getKey(), toKey(entry.getKey(), entry.getValue()));
    }
    if (!parsed.containsKey(currentKeyVersion)) {
      throw new IllegalArgumentException("현재 키 버전(" + currentKeyVersion + ")이 키 목록에 없습니다.");
    }
    this.currentKeyVersion = currentKeyVersion;
    this.keys = Map.copyOf(parsed);
  }

  /**
   * 현재 키로 암호화한다. 빈 문자열은 허용하고 null은 거절(NullPointerException)
   * 같은 평문도 호출할 때마다 결과가 다름 (IV를 매번 새로 만듦)
   */
  public String encrypt(String plaintext) {
    Objects.requireNonNull(plaintext, "plaintext");
    String prefix = FORMAT_VERSION + "." + currentKeyVersion;

    // 같은 키로 IV를 다시 쓰면 GCM 안전성이 깨지므로 매번 SecureRandom으로 새로 만듦
    byte[] iv = new byte[IV_BYTES];
    RANDOM.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, keys.get(currentKeyVersion), new GCMParameterSpec(TAG_BITS, iv));
      // 접두사는 암호화하지 않지만 AAD로 넣어 위변조 검증에 포함 (doFinal 전에 호출)
      cipher.updateAAD(prefix.getBytes(StandardCharsets.UTF_8));
      // 결과 = 암호문 + 태그 16바이트
      byte[] ciphertextWithTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] encryptedBytes = ByteBuffer.allocate(IV_BYTES + ciphertextWithTag.length)
          .put(iv)
          .put(ciphertextWithTag)
          .array();
      return prefix + ":" + Base64.getEncoder().encodeToString(encryptedBytes);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("이벤트 필드 암호화에 실패했습니다.");
    }
  }

  /**
   * encrypt()로 만든 값을 푼다. 키 교체 후에도 키 목록에 남아 있는 옛 키로 만든 값은 풀림
   * 형식이 다르거나/ 모르는 키 버전이거나/ 위변조됐으면 EventFieldCipherException
   */
  public String decrypt(String encrypted) {
    if (encrypted == null) {
      throw new EventFieldCipherException("암호문이 없습니다.");
    }
    // "v1.k1:본문"을 접두사(v1.k1)와 본문으로 나눈다. 점은 콜론보다 앞에 있어야 함
    int colonIndex = encrypted.indexOf(':');
    int dotIndex = colonIndex < 0 ? -1 : encrypted.indexOf('.');
    if (colonIndex < 0 || dotIndex < 0 || dotIndex > colonIndex) {
      throw new EventFieldCipherException("암호문 형식이 아닙니다.");
    }
    String prefix = encrypted.substring(0, colonIndex);
    if (!FORMAT_VERSION.equals(prefix.substring(0, dotIndex))) {
      throw new EventFieldCipherException("지원하지 않는 암호문 형식 버전입니다.");
    }
    SecretKey key = keys.get(prefix.substring(dotIndex + 1));
    if (key == null) {
      throw new EventFieldCipherException("키 목록에 없는 키 버전입니다.");
    }

    byte[] encryptedBytes;
    try {
      encryptedBytes = Base64.getDecoder().decode(encrypted.substring(colonIndex + 1));
    } catch (IllegalArgumentException e) {
      throw new EventFieldCipherException("암호문이 Base64 형식이 아닙니다.");
    }
    if (encryptedBytes.length < IV_BYTES + TAG_BYTES) {
      throw new EventFieldCipherException("암호문 길이가 너무 짧습니다.");
    }

    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      // encryptedBytes = IV 12바이트 + 암호문 + 태그. 앞 12바이트를 IV로 씀
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, encryptedBytes, 0, IV_BYTES));
      // 암호화 때와 같은 접두사를 AAD로 넣어야 태그가 맞음 (접두사가 바뀌었으면 실패)
      cipher.updateAAD(prefix.getBytes(StandardCharsets.UTF_8));
      // IV 뒤부터 끝까지(암호문 + 태그)를 풀고 태그를 검증
      byte[] plain = cipher.doFinal(encryptedBytes, IV_BYTES, encryptedBytes.length - IV_BYTES);
      return new String(plain, StandardCharsets.UTF_8);
    } catch (AEADBadTagException e) {
      throw new EventFieldCipherException("암호문이 위변조됐거나 키가 맞지 않습니다.");
    } catch (GeneralSecurityException e) {
      throw new EventFieldCipherException("이벤트 필드 복호화에 실패했습니다.");
    }
  }

  private static void requireKeyVersion(String version) {
    if (version == null || !KEY_VERSION.matcher(version).matches()) {
      throw new IllegalArgumentException("키 버전은 영문 소문자 · 숫자만 쓸 수 있습니다.");
    }
  }

  private static SecretKey toKey(String version, String base64) {
    if (base64 == null || base64.isBlank()) {
      throw new IllegalArgumentException("키(" + version + ")가 비어 있습니다.");
    }
    if (base64.startsWith("${")) {
      throw new IllegalArgumentException("키(" + version + ")의 환경변수가 설정되지 않았습니다.");
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(base64.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("키(" + version + ")가 Base64 형식이 아닙니다.");
    }
    if (bytes.length != KEY_BYTES) {
      throw new IllegalArgumentException("키(" + version + ")는 Base64로 디코딩해 32바이트여야 합니다.");
    }
    return new SecretKeySpec(bytes, "AES");
  }
}

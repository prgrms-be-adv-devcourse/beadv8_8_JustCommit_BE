package com.justcommit.backend.member.infrastructure.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

// 계좌번호 AES-256-GCM 암호화·복호화
// 저장 형식: "v1:" + Base64(IV 12바이트 + 암호문 + 인증 태그 16바이트)
@Component
public class AccountCipher {

  // AES(알고리즘), GCM(모드), 패딩없음/ GCM은 패딩이 필요 없는 방식
  private static final String ALGORITHM = "AES/GCM/NoPadding";
  private static final int KEY_LENGTH_BYTES = 32; // AES-256
  private static final int IV_LENGTH_BYTES = 12;  // GCM 권장 IV 길이
  private static final int TAG_LENGTH_BITS = 128; // 인증 태그 길이
  private static final String VERSION_PREFIX = "v1:";

  private final SecretKey key;
  private final SecureRandom secureRandom = new SecureRandom();

  // 키가 없으면 Spring이 placeholder를 못 찾아 실행 실패, 형식·길이가 틀리면 여기서 실행 실패
  public AccountCipher(@Value("${account.encryption-key}") String base64Key) {
    byte[] keyBytes;
    try {
      keyBytes = Base64.getDecoder().decode(base64Key);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("account.encryption-key는 Base64 형식이어야 합니다.", e);
    }
    if (keyBytes.length != KEY_LENGTH_BYTES) {
      throw new IllegalStateException("account.encryption-key는 32바이트여야 합니다.");
    }
    this.key = new SecretKeySpec(keyBytes, "AES");
  }

  public String encrypt(String plainText) {
    byte[] iv = new byte[IV_LENGTH_BYTES];
    secureRandom.nextBytes(iv); // 암호화할 때마다 새 IV (같은 키로 IV 재사용 금지)
    try {
      Cipher cipher = Cipher.getInstance(ALGORITHM);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8)); // 암호문 + 태그

      byte[] combined = ByteBuffer.allocate(iv.length + encrypted.length)
              .put(iv)
              .put(encrypted)
              .array();
      return VERSION_PREFIX + Base64.getEncoder().encodeToString(combined);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("계좌번호 암호화에 실패했습니다.", e);
    }
  }

  public String decrypt(String cipherText) {
    if (cipherText == null || !cipherText.startsWith(VERSION_PREFIX)) {
      throw new IllegalStateException("지원하지 않는 계좌 암호문 형식입니다.");
    }
    try {
      byte[] combined = Base64.getDecoder().decode(cipherText.substring(VERSION_PREFIX.length()));
      ByteBuffer buffer = ByteBuffer.wrap(combined);

      byte[] iv = new byte[IV_LENGTH_BYTES];
      buffer.get(iv);
      byte[] encrypted = new byte[buffer.remaining()];
      buffer.get(encrypted);

      Cipher cipher = Cipher.getInstance(ALGORITHM);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException | java.nio.BufferUnderflowException e) {
      // 위변조(태그 불일치), 다른 키, 깨진 Base64 등 → 원문은 메시지에 넣지 않음
      throw new IllegalStateException("계좌번호 복호화에 실패했습니다.", e);
    }
  }

}

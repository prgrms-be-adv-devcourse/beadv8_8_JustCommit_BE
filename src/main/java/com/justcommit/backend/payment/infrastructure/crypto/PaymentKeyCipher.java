
package com.justcommit.backend.payment.infrastructure.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
public class PaymentKeyCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;
    private static final String PREFIX = "v1:";

    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public PaymentKeyCipher(
            @Value("${custom.payment.toss.key-encryption-key}") String base64Key
    ) {
        byte[] keyBytes;

        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("결제키 암호화 키 형식이 올바르지 않습니다.", e);
        }

        if (keyBytes.length != 32) {
            throw new IllegalStateException("결제키 암호화 키는 32바이트여야 합니다.");
        }

        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            throw new IllegalArgumentException("결제키가 비어 있습니다.");
        }

        byte[] iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);

        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey,
                    new GCMParameterSpec(TAG_LENGTH, iv));

            byte[] encrypted = cipher.doFinal(
                    plainText.getBytes(StandardCharsets.UTF_8));

            byte[] combined = ByteBuffer
                    .allocate(iv.length + encrypted.length)
                    .put(iv)
                    .put(encrypted)
                    .array();

            return PREFIX + Base64.getEncoder().encodeToString(combined);

        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("결제키 암호화에 실패했습니다.", e);
        }
    }

    public String decrypt(String encryptedText) {
        if (encryptedText == null || !encryptedText.startsWith(PREFIX)) {
            throw new IllegalStateException("올바르지 않은 암호문입니다.");
        }

        try {
            byte[] combined = Base64.getDecoder()
                    .decode(encryptedText.substring(PREFIX.length()));

            if (combined.length < IV_LENGTH + TAG_LENGTH / 8) {
                throw new IllegalArgumentException("암호문 길이가 올바르지 않습니다.");
            }

            byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(
                    combined, IV_LENGTH, combined.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey,
                    new GCMParameterSpec(TAG_LENGTH, iv));

            return new String(
                    cipher.doFinal(encrypted),
                    StandardCharsets.UTF_8);

        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("결제키 복호화에 실패했습니다.", e);
        }
    }
}

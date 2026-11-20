package com.justcommit.backend.payment.infrastructure.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountNoCipherTest {

    private static final String ACCOUNT_NO = "11122233334444";

    @Test
    @DisplayName("암호화한 계좌번호를 같은 키로 복호화하면 원문이고, 암호문에는 원문이 없다")
    void roundTrip() {
        AccountNoCipher cipher = new AccountNoCipher(newKey());

        String encrypted = cipher.encrypt(ACCOUNT_NO);

        assertThat(encrypted).startsWith("v1:").doesNotContain(ACCOUNT_NO);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(ACCOUNT_NO);
    }

    @Test
    @DisplayName("다른 키(결제키 암호화 키 등)로는 계좌번호 암호문을 복호화할 수 없다")
    void otherKeyCannotDecrypt() {
        String encrypted = new AccountNoCipher(newKey()).encrypt(ACCOUNT_NO);

        assertThatThrownBy(() -> new PaymentKeyCipher(newKey()).decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(ACCOUNT_NO);
    }

    @Test
    @DisplayName("키가 없거나 32바이트가 아니면 시작할 때 실패한다")
    void rejectsInvalidKey() {
        assertThatThrownBy(() -> new AccountNoCipher(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("계좌번호 암호화 키");
        assertThatThrownBy(() -> new AccountNoCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32바이트");
    }

    private static String newKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}

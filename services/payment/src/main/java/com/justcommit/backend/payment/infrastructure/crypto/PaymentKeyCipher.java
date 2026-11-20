package com.justcommit.backend.payment.infrastructure.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 토스 결제키(charge.pg_payment_key_enc) 암호화. 키: PAYMENT_KEY_ENCRYPTION_KEY
@Component
public class PaymentKeyCipher extends AesGcmCipher {

    public PaymentKeyCipher(@Value("${custom.payment.toss.key-encryption-key}") String base64Key) {
        super(base64Key, "결제키");
    }
}

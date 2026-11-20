package com.justcommit.backend.payment.infrastructure.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 인출 계좌번호(cash_out.account_no_enc) 암호화. 키: CASH_OUT_ACCOUNT_ENCRYPTION_KEY
// 결제키와 키를 나눠, 한쪽 키를 바꿀 때 다른 쪽 암호문은 다시 암호화하지 않아도 되게 한다
@Component
public class AccountNoCipher extends AesGcmCipher {

    public AccountNoCipher(@Value("${custom.payment.cash-out.account-encryption-key}") String base64Key) {
        super(base64Key, "계좌번호");
    }
}

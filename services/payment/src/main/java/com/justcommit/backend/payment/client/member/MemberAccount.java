package com.justcommit.backend.payment.client.member;

// member가 복호화해 보낸 계좌. accountNo는 원문이라 저장 전에 암호화하고, 응답, 로그에는 뒤 4자리만 쓴다
public record MemberAccount(String bankCode, String accountNo, String accountHolder) {

    public String accountLast4() {
        return last4(accountNo);
    }

    public static String last4(String accountNo) {
        if (accountNo == null) {
            return null;
        }
        return accountNo.length() <= 4 ? accountNo : accountNo.substring(accountNo.length() - 4);
    }

    // 로그, 디버깅 출력에 계좌번호 원문이 찍히지 않게
    @Override
    public String toString() {
        return "MemberAccount[bankCode=" + bankCode + ", accountNo=****" + accountLast4() + "]";
    }
}

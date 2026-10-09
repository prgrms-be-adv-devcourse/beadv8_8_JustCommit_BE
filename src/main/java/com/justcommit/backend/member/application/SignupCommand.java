package com.justcommit.backend.member.application;

// 회원가입 입력 (presentation의 SignupRequest → application)
// 평문 계좌번호(서비스에서 암호화)
public record SignupCommand(
        String email, String password, String nickname, String phone,
        String bankCode, String accountNo, String accountHolder
) {
}

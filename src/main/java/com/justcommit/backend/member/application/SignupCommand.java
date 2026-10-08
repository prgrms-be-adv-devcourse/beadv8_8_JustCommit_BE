package com.justcommit.backend.member.application;

// 회원가입 입력값 (계좌·배송지는 후속 이슈에서 필드 추가)
public record SignupCommand(
        String email, String password, String nickname, String phone
) {
}

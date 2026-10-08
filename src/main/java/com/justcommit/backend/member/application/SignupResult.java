package com.justcommit.backend.member.application;

// 회원가입 결과
public record SignupResult(
        Long memberId, String nickname) {
}

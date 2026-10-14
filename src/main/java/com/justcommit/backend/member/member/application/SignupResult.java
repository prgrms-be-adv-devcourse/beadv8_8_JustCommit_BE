package com.justcommit.backend.member.member.application;

// 회원가입 결과
public record SignupResult(
        Long memberId, String nickname) {
}

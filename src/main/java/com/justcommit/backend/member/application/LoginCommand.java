package com.justcommit.backend.member.application;

// 로그인 입력(presentation의 LoginRequest → application)
public record LoginCommand(String email, String password) {
}

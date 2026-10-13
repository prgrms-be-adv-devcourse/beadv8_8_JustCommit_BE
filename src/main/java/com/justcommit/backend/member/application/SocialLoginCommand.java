package com.justcommit.backend.member.application;

import com.justcommit.backend.member.domain.Provider;

// 소셜 로그인 요청 (가입 경로, 소셜 회원 식별값, 소셜 계정 이메일)
public record SocialLoginCommand(Provider provider, String providerId, String email) {
}

package com.justcommit.backend.member.account.application;

// 계좌 변경 토큰 발급 결과 (토큰 원문은 이 응답으로만 전달하고 로그에 남기지 않는다)
public record AccountChangeTokenResult(String accountChangeToken, long expiresIn) {
}

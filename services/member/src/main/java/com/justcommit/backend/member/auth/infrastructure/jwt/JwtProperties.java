package com.justcommit.backend.member.auth.infrastructure.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// jwt.* 설정: 발급용 개인키, Signup 토큰 검증용 공개키, 만료 시간
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String privateKey,
        String publicKey,
        Duration accessTokenExpiration,
        Duration signupTokenExpiration
) {
}

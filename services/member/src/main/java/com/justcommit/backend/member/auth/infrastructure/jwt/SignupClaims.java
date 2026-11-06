package com.justcommit.backend.member.auth.infrastructure.jwt;

public record SignupClaims(String provider, String providerId, String email) {
}

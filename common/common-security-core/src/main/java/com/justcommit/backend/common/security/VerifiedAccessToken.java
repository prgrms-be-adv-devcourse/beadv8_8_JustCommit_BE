package com.justcommit.backend.common.security;

/**
 * 검증을 통과한 Access 토큰.
 *
 * @param member   회원 ID · 권한
 * @param issuedAt 발급 시각 (epoch 초, 토큰의 iat). 회원 단위 폐기(`iat <= 끊은 시각`) 비교에 씀
 */
public record VerifiedAccessToken(AuthMember member, long issuedAt) {
}

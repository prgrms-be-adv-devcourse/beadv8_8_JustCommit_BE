/**
 * 인증·인가 공개 API.
 *
 * 다른 모듈은 이 패키지의 AuthMember, JwtProvider, TokenType만 사용한다.
 * 하위 패키지(jwt, handler)는 내부 구현이다.
 */
@org.springframework.modulith.NamedInterface("security")
package com.justcommit.backend.common.security;
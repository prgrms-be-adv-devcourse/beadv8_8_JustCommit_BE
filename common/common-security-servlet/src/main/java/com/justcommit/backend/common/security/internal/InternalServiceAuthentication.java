package com.justcommit.backend.common.security.internal;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

/**
 * X-Internal-Key로 인증된 서비스 간 호출. 회원 정보(AuthMember)는 담지 않는다.
 * 컨트롤러의 @AuthenticationPrincipal AuthMember는 이 인증에서 null이 된다.
 */
public final class InternalServiceAuthentication extends AbstractAuthenticationToken {

  public static final String ROLE = "ROLE_INTERNAL";
  private static final String PRINCIPAL = "internal-service";

  public InternalServiceAuthentication() {
    super(AuthorityUtils.createAuthorityList(ROLE));
    setAuthenticated(true);
  }

  // 키 값은 담지 않음
  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public Object getPrincipal() {
    return PRINCIPAL;
  }
}

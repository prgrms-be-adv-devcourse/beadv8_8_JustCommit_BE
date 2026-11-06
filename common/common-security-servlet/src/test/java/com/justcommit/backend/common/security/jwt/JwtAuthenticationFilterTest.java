package com.justcommit.backend.common.security.jwt;

import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.common.security.JwtVerifier;
import com.justcommit.backend.common.security.TokenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class JwtAuthenticationFilterTest {

  private final JwtVerifier jwtVerifier = mock(JwtVerifier.class);
  private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtVerifier);

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("정상 토큰이면 로그인 회원과 ROLE_ 권한을 SecurityContext에 넣는다")
  void validToken() throws Exception {
    given(jwtVerifier.parseAccessToken("good")).willReturn(new AuthMember(4L, "SELLER"));
    MockHttpServletRequest request = requestWithToken("Bearer good");
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    assertThat(authentication.getPrincipal()).isEqualTo(new AuthMember(4L, "SELLER"));
    assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_SELLER");
    assertThat(chain.getRequest()).isNotNull(); // 다음 필터로 넘어감
  }

  @Test
  @DisplayName("잘못된 토큰이면 로그인 정보 없이 실패 이유만 기록하고 다음 필터로 넘긴다")
  void invalidToken() throws Exception {
    given(jwtVerifier.parseAccessToken("bad"))
            .willThrow(new TokenException(TokenException.Reason.EXPIRED));
    MockHttpServletRequest request = requestWithToken("Bearer bad");
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(request.getAttribute(JwtAuthenticationFilter.TOKEN_ERROR_ATTRIBUTE))
            .isEqualTo(TokenException.Reason.EXPIRED);
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  @DisplayName("Authorization 헤더가 없거나 Bearer가 아니면 검증하지 않고 넘긴다")
  void noBearerToken() throws Exception {
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
    filter.doFilter(requestWithToken("Basic abc"), new MockHttpServletResponse(), new MockFilterChain());

    verifyNoInteractions(jwtVerifier);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(chain.getRequest()).isNotNull();
  }

  private static MockHttpServletRequest requestWithToken(String header) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", header);
    return request;
  }
}

package com.justcommit.backend.common.security.jwt;

import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.common.security.JwtProvider;
import com.justcommit.backend.common.security.TokenException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

// 요청마다 한 번 실행: Authorization 헤더의 Access 토큰을 검증하고 SecurityContext에 로그인 회원 저장
// @Component X -> SecurityConfig에서 직접 생성해 Security 필터 체인에만 등록
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  public static final String TOKEN_ERROR_ATTRIBUTE = "jwt.error";
  private static final String BEARER_PREFIX = "Bearer ";
  private final JwtProvider jwtProvider;

  public JwtAuthenticationFilter(JwtProvider jwtProvider) {
    this.jwtProvider = jwtProvider;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain filterChain) throws ServletException, IOException {

    String token = resolveToken(request);

    if (token != null) {
      try {
        AuthMember authMember = jwtProvider.parseAccessToken(token);

        // 권한은 "ROLE_" 접두사를 붙여야 hasRole("SELLER")가 인식함
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        authMember,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + authMember.role()))
                );
        SecurityContextHolder.getContext().setAuthentication(authentication);

      } catch (TokenException e) {
        // 이유만 기록. 인증이 필요한 경로라면 EntryPoint가 401을 응답. (permitAll 경로는 토큰이 이상해도 비회원으로 통과)
        request.setAttribute(TOKEN_ERROR_ATTRIBUTE, e.getReason());
      }
    }

    filterChain.doFilter(request, response);
  }

  private String resolveToken(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header != null && header.startsWith(BEARER_PREFIX)) {
      return header.substring(BEARER_PREFIX.length());
    }
    return null;
  }
}

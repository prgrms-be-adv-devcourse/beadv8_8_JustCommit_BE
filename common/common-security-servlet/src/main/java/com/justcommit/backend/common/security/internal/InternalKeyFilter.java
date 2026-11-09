package com.justcommit.backend.common.security.internal;

import com.justcommit.backend.common.security.handler.InternalKeyFailureHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

// /internal/** 요청만 X-Internal-Key로 확인 (회원 토큰 검사는 하지 않음 — docs/conventions/auth.md)
// JWT 필터 다음에 실행: 유효한 회원 토큰이면 AuthMember를 유지하고, 없거나 잘못됐으면 내부 서비스 인증으로 처리
public class InternalKeyFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Internal-Key";
  private static final Logger log = LoggerFactory.getLogger(InternalKeyFilter.class);
  private static final RequestMatcher INTERNAL_PATHS =
      PathPatternRequestMatcher.withDefaults().matcher("/internal/**");

  // 설정이 없거나 무효면 null -> /internal/** 요청을 모두 거부 (앱은 뜸)
  private final byte[] expectedKey;

  public InternalKeyFilter(String configuredKey) {
    if (isUsable(configuredKey)) {
      this.expectedKey = configuredKey.getBytes(StandardCharsets.UTF_8);
    } else {
      this.expectedKey = null;
      log.warn("internal-api.key가 설정되지 않아 /internal/** 요청을 모두 거부합니다.");
    }
  }

  // /internal/**이 아닌 요청은 이 필터를 건너뜀
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !INTERNAL_PATHS.matches(request);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain filterChain) throws ServletException, IOException {

    // 1. 키가 먼저: 없거나 틀리면 회원 토큰과 상관없이 401
    if (!matchesKey(request.getHeader(HEADER))) {
      InternalKeyFailureHandler.reject(response);
      return;
    }

    // 2. 키가 맞음: 유효한 회원 토큰이 있으면 그대로, 없거나 잘못됐으면 내부 서비스 인증
    Authentication current = SecurityContextHolder.getContext().getAuthentication();
    if (current == null || !current.isAuthenticated()) {
      SecurityContextHolder.getContext().setAuthentication(new InternalServiceAuthentication());
    }

    filterChain.doFilter(request, response);
  }

  // 시간이 일정한 비교: 비교에 걸린 시간으로 키를 추측하지 못하게
  private boolean matchesKey(String providedKey) {
    if (expectedKey == null || providedKey == null) {
      return false;
    }
    return MessageDigest.isEqual(expectedKey, providedKey.getBytes(StandardCharsets.UTF_8));
  }

  // 빈 값 · 공백 · 해석되지 않은 자리 표시자("${INTERNAL_API_KEY}")는 설정 없음으로 취급
  private static boolean isUsable(String key) {
    return key != null && !key.isBlank() && !key.startsWith("${");
  }
}

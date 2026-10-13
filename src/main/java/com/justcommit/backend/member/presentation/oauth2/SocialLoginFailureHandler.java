package com.justcommit.backend.member.presentation.oauth2;

import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

// 소셜 로그인 인증 실패
// 응답은 이유와 관계없이 401 SOCIAL_LOGIN_FAILED(실제 이유는 서버 로그에만)
// common의 SecurityConfig가 AuthenticationFailureHandler 타입으로 찾아서 연결
@Slf4j
@Component
public class SocialLoginFailureHandler implements AuthenticationFailureHandler  {

  @Override
  public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                      AuthenticationException exception) throws IOException {
    log.warn("소셜 로그인 실패: {}", failureReason(exception));
    OAuth2JsonResponseWriter.writeError(response, MemberErrorCode.SOCIAL_LOGIN_FAILED);
  }

  // OAuth2 표준 에러 코드가 있으면 그 값, 없으면 예외 종류
  private String failureReason(AuthenticationException exception) {
    if (exception instanceof OAuth2AuthenticationException oauth2Exception) {
      return oauth2Exception.getError().getErrorCode();
    }
    return exception.getClass().getSimpleName();
  }
}

package com.justcommit.backend.member.config;

import com.justcommit.backend.common.security.config.CommonSecurityConfigurer;
import com.justcommit.backend.member.auth.infrastructure.oauth2.CookieOAuth2AuthorizationRequestRepository;
import com.justcommit.backend.member.auth.presentation.oauth2.SocialLoginFailureHandler;
import com.justcommit.backend.member.auth.presentation.oauth2.SocialLoginSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

// member 서비스 보안 설정: 공통 설정(필터 · 401/403 · 세션 없음)을 적용하고 member 공개 경로만 적는다
@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, CommonSecurityConfigurer common,
                                          CookieOAuth2AuthorizationRequestRepository authorizationRequestRepository,
                                          SocialLoginSuccessHandler socialLoginSuccessHandler,
                                          SocialLoginFailureHandler socialLoginFailureHandler) throws Exception {
    common.apply(http);
    return http
        .authorizeHttpRequests(auth -> auth
            // 이메일 인증 · 로그인 · 재발급 · 로그아웃, 카카오 로그인
            .requestMatchers("/api/v1/auth/**", "/oauth2/**", "/login/oauth2/**").permitAll()

            // 회원가입 · 소셜 가입(가입 토큰은 Access가 아니라 필터에서 통과되고 서비스가 직접 확인)
            .requestMatchers(HttpMethod.POST, "/api/v1/users", "/api/v1/users/social").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/users/nicknameAvailability", "/api/v1/users/phoneAvailability").permitAll()
            // Swagger (운영 노출 여부는 미정)
            .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
            .anyRequest().authenticated())

        // 카카오 로그인 (/oauth2/authorization/kakao → 카카오 → /login/oauth2/code/kakao)
        // state는 세션 대신 서명 쿠키에 저장 (세션 없음 유지)
        .oauth2Login(oauth2 -> oauth2
            .authorizationEndpoint(endpoint -> endpoint
                .authorizationRequestRepository(authorizationRequestRepository))
            .successHandler(socialLoginSuccessHandler)
            .failureHandler(socialLoginFailureHandler))
        .build();
  }

  // 비밀번호 BCrypt 해시 (회원가입 · 로그인)
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}

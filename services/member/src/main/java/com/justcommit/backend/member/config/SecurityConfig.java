package com.justcommit.backend.member.config;

import com.justcommit.backend.common.security.config.CommonSecurityConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

// member 서비스 보안 설정: 공통 설정(필터 · 401/403 · 세션 없음)을 적용하고 member 공개 경로만 적는다
@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, CommonSecurityConfigurer common) throws Exception {
    common.apply(http);
    return http
        .authorizeHttpRequests(auth -> auth
            // 이메일 인증 · 로그인 · 재발급 · 로그아웃, 카카오 로그인
            .requestMatchers("/api/v1/auth/**", "/oauth2/**", "/login/oauth2/**").permitAll()

            // 회원가입 · 소셜 가입(가입 토큰은 Access가 아니라 필터에서 통과되고 서비스가 직접 확인)
            .requestMatchers(HttpMethod.POST, "/api/v1/users", "/api/v1/users/social").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/users/checkNickname", "/api/v1/users/checkPhone").permitAll()

            // Swagger (운영 노출 여부는 미정)
            .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
            .anyRequest().authenticated())
        .build();
  }
}

package com.justcommit.backend.notification.config;

import com.justcommit.backend.common.security.config.CommonSecurityConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

// notification 서비스 보안 설정: 공통 설정(필터 · 401/403 · 세션 없음 · SSE 비동기 디스패치)을 적용한다
// 알림 API는 모두 로그인 필요라 공개 경로가 없다
@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, CommonSecurityConfigurer common) throws Exception {
    common.apply(http);
    return http
        .authorizeHttpRequests(auth -> auth
            .anyRequest().authenticated())
        .build();
  }
}

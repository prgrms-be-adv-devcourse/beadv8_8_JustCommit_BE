package com.justcommit.backend.ai.config;

import com.justcommit.backend.common.security.config.CommonSecurityConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

// ai 서비스 보안 설정: 공통 설정(필터 · 401/403 · 세션 없음)을 적용한다
// 외부 API가 없고 /internal/**은 공통 내부 키 필터가 인증하므로 공개 경로가 없다
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

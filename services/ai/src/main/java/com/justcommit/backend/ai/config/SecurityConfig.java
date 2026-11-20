package com.justcommit.backend.ai.config;

import com.justcommit.backend.common.security.config.CommonSecurityConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

// ai 서비스 보안 설정: 공통 설정(필터 · 401/403 · 세션 없음)을 적용한다
// 외부 API는 품종 의미 검색(공개)뿐이고, /internal/**은 공통 내부 키 필터가 인증한다
// 공개 경로는 Gateway PublicPaths와 같아야 한다
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CommonSecurityConfigurer common) throws Exception {
        common.apply(http);
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/speciesSearches").permitAll()
                        .anyRequest().authenticated())
                .build();
    }
}

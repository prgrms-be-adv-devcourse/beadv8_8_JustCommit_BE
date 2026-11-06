package com.justcommit.backend.product.config;

import com.justcommit.backend.common.security.config.CommonSecurityConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

// product 서비스 보안 설정: 공통 설정(필터 · 401/403 · 세션 없음)을 적용하고 product 공개 경로만 적는다
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CommonSecurityConfigurer common) throws Exception {
        common.apply(http);
        return http
                .authorizeHttpRequests(auth -> auth
                        // 내 상품 조회는 /products/** 공개 규칙보다 먼저 적어야 로그인이 필요해진다
                        .requestMatchers(HttpMethod.GET, "/api/v1/products/me", "/api/v1/products/me/**").authenticated()
                        // 상품 · 품종 조회는 비회원도 가능 (상품 상세는 토큰이 있으면 숨긴 내 상품도 보임)
                        .requestMatchers(HttpMethod.GET, "/api/v1/products", "/api/v1/products/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/species", "/api/v1/species/**").permitAll()
                        .anyRequest().authenticated())
                .build();
    }
}

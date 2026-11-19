package com.justcommit.backend.market.config;

import com.justcommit.backend.common.security.config.CommonSecurityConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

// market 서비스 보안 설정: 공통 설정(필터 · 401/403 · 세션 없음)을 적용하고 market 공개 경로만 적는다
@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, CommonSecurityConfigurer common) throws Exception {
    common.apply(http);
    return http
        .authorizeHttpRequests(auth -> auth
            // 택배사 웹훅 (토큰 대신 컨트롤러가 X-Api-Key를 직접 확인)
            .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/courier").permitAll()
            // Prometheus 지표 수집. Gateway가 /actuator를 넘기지 않아 내부망(Prometheus)에서만 닿는다
            .requestMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll()
            // 판매자 수락, 거절은 SELLER 토큰만 (#284). 본인 판매자 주문인지는 서비스가 다시 확인한다
            .requestMatchers(HttpMethod.POST, "/api/v1/orders/seller/*/accept", "/api/v1/orders/seller/*/reject")
                .hasRole("SELLER")
            .anyRequest().authenticated())
        .build();
  }
}

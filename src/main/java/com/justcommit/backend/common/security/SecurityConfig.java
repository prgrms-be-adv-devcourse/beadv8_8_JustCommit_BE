package com.justcommit.backend.common.security;

import com.justcommit.backend.common.security.handler.JwtAccessDeniedHandler;
import com.justcommit.backend.common.security.handler.JwtAuthenticationEntryPoint;
import com.justcommit.backend.common.security.jwt.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// JWT 기반 인증·인가 설정
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // 인증 없이 접근 가능한 경로 (설계 문서 6. permitAll 목록)
    private static final String[] PUBLIC_PATHS = {
            "/actuator/health/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/api/v1/auth/**",
            "/oauth2/**",
            "/login/oauth2/**"
    };

    private final JwtProvider jwtProvider;

    public SecurityConfig(JwtProvider jwtProvider) {
        this.jwtProvider = jwtProvider;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // 세션·폼 로그인을 쓰지 않는 REST API이므로 끔
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)

                // 서버에 로그인 상태를 저장하지 않음. 매 요청 토큰으로 인증
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 401, 403 JSON 응답
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(new JwtAuthenticationEntryPoint())
                        .accessDeniedHandler(new JwtAccessDeniedHandler()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/users", "/api/v1/users/social").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/checkNickname").permitAll()
                        .requestMatchers("/api/v1/users/me").authenticated()
                        // TODO: 로그인 API 머지 후 anyRequest().authenticated()로 전환
                        // (로그인 수단이 없는 동안 다른 모듈 API 개발을 막지 않기 위해 임시 허용)
                        .anyRequest().permitAll())
                // 아이디·비밀번호 필터보다 먼저 JWT 필터 실행
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    // 비밀번호 BCrypt 해시. member 모듈의 회원가입·로그인에서 주입받아 사용
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}


package com.justcommit.backend.common.security;

import com.justcommit.backend.common.security.handler.JwtAccessDeniedHandler;
import com.justcommit.backend.common.security.handler.JwtAuthenticationEntryPoint;
import com.justcommit.backend.common.security.jwt.JwtAuthenticationFilter;
import com.justcommit.backend.common.security.oauth2.CookieOAuth2AuthorizationRequestRepository;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// JWT 기반 인증·인가 설정
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // 인증 없이 접근 가능한 경로 (permitAll 목록)
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
    private final CookieOAuth2AuthorizationRequestRepository authorizationRequestRepository;

    // 소셜 로그인 성공, 실패 핸들러. member 모듈에서 빈으로 등록하면 연결됨 (없으면 Spring 기본 동작)
    private final ObjectProvider<AuthenticationSuccessHandler> oauth2SuccessHandler;
    private final ObjectProvider<AuthenticationFailureHandler> oauth2FailureHandler;

    public SecurityConfig(JwtProvider jwtProvider,
                          CookieOAuth2AuthorizationRequestRepository authorizationRequestRepository,
                          ObjectProvider<AuthenticationSuccessHandler> oauth2SuccessHandler,
                          ObjectProvider<AuthenticationFailureHandler> oauth2FailureHandler) {
        this.jwtProvider = jwtProvider;
        this.authorizationRequestRepository = authorizationRequestRepository;
        this.oauth2SuccessHandler = oauth2SuccessHandler;
        this.oauth2FailureHandler = oauth2FailureHandler;
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
                        // 기존 코드
//                        .requestMatchers("/api/v1/users/me").authenticated()
//                        .requestMatchers("/api/v1/cart", "/api/v1/cart/**").authenticated()
//                        .requestMatchers(HttpMethod.GET, "/api/v1/products", "/api/v1/products/**").permitAll()
//                        .requestMatchers("/api/v1/products", "/api/v1/products/**").authenticated()
//                        .anyRequest().permitAll())
                        // 인증 필수 전환 허용할 경로만 적고 나머지는 모두 로그인 필요
                        .requestMatchers(HttpMethod.GET, "/api/v1/products", "/api/v1/products/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/species", "/api/v1/species/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/courier").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN") // 관리자 전용 (정산 API 경로 확정 시 조정)
                        .anyRequest().authenticated()) // permitAll → authenticated


                // 소셜 로그인 (/oauth2/authorization/kakao → 카카오 → /login/oauth2/code/kakao)
                .oauth2Login(oauth2 -> {
                    // state를 서명 쿠키에 저장 (기본값 HttpSession 대신)
                    oauth2.authorizationEndpoint(endpoint ->
                            endpoint.authorizationRequestRepository(authorizationRequestRepository));
                    // 핸들러 빈이 정확히 1개 있을 때만 연결
                    oauth2SuccessHandler.ifUnique(oauth2::successHandler);
                    oauth2FailureHandler.ifUnique(oauth2::failureHandler);
                })

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


package com.justcommit.backend.common.security.config;

import com.justcommit.backend.common.security.JwtVerifier;
import com.justcommit.backend.common.security.handler.JwtAccessDeniedHandler;
import com.justcommit.backend.common.security.handler.JwtAuthenticationEntryPoint;
import com.justcommit.backend.common.security.internal.InternalKeyFilter;
import com.justcommit.backend.common.security.jwt.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 모든 서비스에 같은 보안 기본 설정을 적용한다.
 * 서비스는 자기 SecurityConfig에서 apply(http)를 부른 뒤 공개 경로와 anyRequest()만 적는다.
 */
public class CommonSecurityConfigurer {

  private final JwtVerifier jwtVerifier;
  private final String internalApiKey;

  public CommonSecurityConfigurer(JwtVerifier jwtVerifier, String internalApiKey) {
    this.jwtVerifier = jwtVerifier;
    this.internalApiKey = internalApiKey;
  }

  public void apply(HttpSecurity http) throws Exception {
    http
        // 세션 · 폼 로그인을 쓰지 않는 REST API이므로 끔
        .csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)

        // 서버에 로그인 상태를 저장하지 않음 (매 요청 토큰으로 인증)
        .sessionManagement(session -> session
            .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

        // 401 · 403을 ApiResponse JSON으로 응답
        .exceptionHandling(exception -> exception
            .authenticationEntryPoint(new JwtAuthenticationEntryPoint())
            .accessDeniedHandler(new JwtAccessDeniedHandler()))

        // 서비스와 무관한 기술 경로. 서비스 규칙은 이 뒤에 이어 붙고 서비스가 anyRequest()로 마무리
        .authorizeHttpRequests(auth -> auth
            // SSE 응답을 보내는 비동기 디스패치는 처음 요청에서 이미 인증을 거쳤으므로 통과
            // (JWT 필터는 비동기 디스패치에서 다시 돌지 않아 막으면 알림 전송 때 401 · 403이 남)
            .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
            .requestMatchers("/actuator/health/**").permitAll())

        // 아이디 · 비밀번호 필터보다 먼저 JWT 필터 실행
        .addFilterBefore(new JwtAuthenticationFilter(jwtVerifier),
            UsernamePasswordAuthenticationFilter.class)

        // JWT 필터 다음에 내부 키 필터 실행 (/internal/**만, 유효한 회원 토큰이면 AuthMember 유지)
        .addFilterAfter(new InternalKeyFilter(internalApiKey), JwtAuthenticationFilter.class);
  }
}

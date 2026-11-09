package com.justcommit.backend.common.security.config;

import com.justcommit.backend.common.security.JwtVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * common-security-servlet을 의존한 서비스에 토큰 검증기와 보안 기본 설정을 자동으로 등록한다.
 * 서비스 패키지와 상관없이 적용된다 (컴포넌트 스캔에 기대지 않음).
 * Boot의 기본 사용자 설정보다 먼저 등록돼야 기본 사용자 생성을 막을 수 있다.
 */
@AutoConfiguration(before = UserDetailsServiceAutoConfiguration.class)
public class CommonSecurityAutoConfiguration {

  // jwt.public-key가 없거나 형식이 틀리면 앱이 시작되지 않음 (공개키 없이 뜨는 것을 막음)
  @Bean
  @ConditionalOnMissingBean
  JwtVerifier jwtVerifier(@Value("${jwt.public-key}") String publicKey) {
    return new JwtVerifier(publicKey);
  }

  // internal-api.key가 없어도 앱은 뜨고, 내부 키 필터가 /internal/** 요청을 모두 거부함
  // @Value는 yml의 ${INTERNAL_API_KEY}를 풀지 못하면 시작에 실패해서 Binder로 읽음
  // (풀지 못한 값은 "${...}" 글자 그대로 넘어오고, 필터가 키 없음으로 처리)
  @Bean
  @ConditionalOnMissingBean
  CommonSecurityConfigurer commonSecurityConfigurer(JwtVerifier jwtVerifier, Environment environment) {
    String internalApiKey = Binder.get(environment)
        .bind("internal-api.key", String.class)
        .orElse(null);
    return new CommonSecurityConfigurer(jwtVerifier, internalApiKey);
  }

  // 인증은 JWT로만 한다. 이 빈이 없으면 Boot가 아이디 user + 무작위 비밀번호인 기본 사용자를 만들고
  // 로그에 비밀번호를 남김("Using generated security password") → 사용자 정보를 주지 않고 항상 거부한다.
  // 서비스가 UserDetailsService를 직접 등록하면 이 빈은 만들어지지 않는다.
  // TODO: Spring Security의 아이디 · 비밀번호 인증(AuthenticationManager 등)을 도입하면 이 빈의 역할을 다시 검토
  @Bean
  @ConditionalOnMissingBean(UserDetailsService.class)
  UserDetailsService rejectingUserDetailsService() {
    return username -> {
      throw new UsernameNotFoundException("아이디 · 비밀번호 인증은 사용하지 않습니다.");
    };
  }
}

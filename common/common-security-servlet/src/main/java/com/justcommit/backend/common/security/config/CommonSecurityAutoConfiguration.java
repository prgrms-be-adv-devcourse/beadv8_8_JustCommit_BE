package com.justcommit.backend.common.security.config;

import com.justcommit.backend.common.security.JwtVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * common-security-servlet을 의존한 서비스에 토큰 검증기와 보안 기본 설정을 자동으로 등록한다.
 * 서비스 패키지와 상관없이 적용된다 (컴포넌트 스캔에 기대지 않음).
 */
@AutoConfiguration
public class CommonSecurityAutoConfiguration {

  // jwt.secret이 없으면 앱이 시작되지 않음 (서명 키 없이 뜨는 것을 막음)
  @Bean
  @ConditionalOnMissingBean
  JwtVerifier jwtVerifier(@Value("${jwt.secret}") String secret) {
    return new JwtVerifier(secret);
  }

  @Bean
  @ConditionalOnMissingBean
  CommonSecurityConfigurer commonSecurityConfigurer(JwtVerifier jwtVerifier) {
    return new CommonSecurityConfigurer(jwtVerifier);
  }
}

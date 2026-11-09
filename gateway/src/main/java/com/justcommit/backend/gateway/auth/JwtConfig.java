package com.justcommit.backend.gateway.auth;

import com.justcommit.backend.common.security.JwtVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtConfig {

  // 공개키가 없거나 형식이 틀리면 JwtVerifier 생성에서 실패 → 앱이 뜨지 않음 (의도)
  @Bean
  public JwtVerifier jwtVerifier(@Value("${jwt.public-key}") String publicKey) {
    return new JwtVerifier(publicKey);
  }
}

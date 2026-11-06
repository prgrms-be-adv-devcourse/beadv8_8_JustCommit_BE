package com.justcommit.backend.common.security.config;

import com.justcommit.backend.common.security.JwtVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class CommonSecurityAutoConfigurationTest {

  private static final String PUBLIC_KEY = publicKey(2048);

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(CommonSecurityAutoConfiguration.class));

  @Test
  @DisplayName("올바른 공개키가 있으면 검증기와 공통 보안 설정이 등록된다")
  void registersBeansWithValidKey() {
    runner.withPropertyValues("jwt.public-key=" + PUBLIC_KEY)
            .run(context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(JwtVerifier.class);
              assertThat(context).hasSingleBean(CommonSecurityConfigurer.class);
            });
  }

  @Test
  @DisplayName("jwt.public-key 설정이 없으면 시작에 실패한다")
  void failsWithoutKey() {
    runner.run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("jwt.public-key가 빈 값이면 시작에 실패한다")
  void failsWithBlankKey() {
    runner.withPropertyValues("jwt.public-key=")
            .run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("공개키 형식이 틀리거나 2048비트 미만이면 시작에 실패한다")
  void failsWithInvalidKey() {
    runner.withPropertyValues("jwt.public-key=not-a-key")
            .run(context -> assertThat(context).hasFailed());
    runner.withPropertyValues("jwt.public-key=" + publicKey(1024))
            .run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("서비스가 JwtVerifier를 직접 등록하면 그것을 쓰고 공개키 설정은 요구하지 않는다")
  void usesServiceDefinedVerifier() {
    runner.withBean(JwtVerifier.class, () -> new JwtVerifier(PUBLIC_KEY))
            .run(context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(JwtVerifier.class);
            });
  }

  private static String publicKey(int bits) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(bits);
      return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

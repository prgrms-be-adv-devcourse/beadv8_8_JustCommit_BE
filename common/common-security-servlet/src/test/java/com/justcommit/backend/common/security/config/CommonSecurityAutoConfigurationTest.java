package com.justcommit.backend.common.security.config;

import com.justcommit.backend.common.security.JwtVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommonSecurityAutoConfigurationTest {

  private static final String PUBLIC_KEY = publicKey(2048);

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(CommonSecurityAutoConfiguration.class));

  // Boot의 기본 사용자 설정은 서블릿 웹 앱에서만 동작해서 웹 앱용 러너를 쓴다
  private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(
                  SecurityAutoConfiguration.class,
                  UserDetailsServiceAutoConfiguration.class));

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
  @DisplayName("internal-api.key가 풀리지 않는 자리 표시자여도 시작은 성공한다 (내부 요청만 거부)")
  void startsWithUnresolvedInternalKey() {
    runner.withPropertyValues("jwt.public-key=" + PUBLIC_KEY, "internal-api.key=${INTERNAL_API_KEY}")
            .run(context -> {
              assertThat(context).hasNotFailed();
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

  @Test
  @DisplayName("대조군: 공통 설정이 없으면 Boot가 기본 사용자를 만든다")
  void bootCreatesDefaultUserWithoutCommonConfig() {
    webRunner.run(context -> assertThat(context).hasSingleBean(InMemoryUserDetailsManager.class));
  }

  @Test
  @DisplayName("공통 설정이 있으면 Boot 기본 사용자를 만들지 않고, 아이디 · 비밀번호 조회를 거부한다")
  void doesNotCreateDefaultUser() {
    webRunner.withConfiguration(AutoConfigurations.of(CommonSecurityAutoConfiguration.class))
            .withPropertyValues("jwt.public-key=" + PUBLIC_KEY)
            .run(context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(InMemoryUserDetailsManager.class);
              UserDetailsService userDetailsService = context.getBean(UserDetailsService.class);
              assertThatThrownBy(() -> userDetailsService.loadUserByUsername("user"))
                      .isInstanceOf(UsernameNotFoundException.class);
            });
  }

  @Test
  @DisplayName("서비스가 UserDetailsService를 직접 등록하면 공통 거부 빈은 만들지 않는다")
  void usesServiceDefinedUserDetailsService() {
    webRunner.withConfiguration(AutoConfigurations.of(CommonSecurityAutoConfiguration.class))
            .withPropertyValues("jwt.public-key=" + PUBLIC_KEY)
            .withBean("serviceUserDetailsService", UserDetailsService.class,
                    () -> username -> User.withUsername(username).password("{noop}pw").roles("MEMBER").build())
            .run(context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(UserDetailsService.class);
              assertThat(context).hasBean("serviceUserDetailsService");
              assertThat(context).doesNotHaveBean("rejectingUserDetailsService");
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

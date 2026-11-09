package com.justcommit.backend.common.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class InternalApiKeyValidationTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(
          HttpClientAutoConfiguration.class,
          ImperativeHttpClientAutoConfiguration.class,
          HttpServiceClientPropertiesAutoConfiguration.class,
          RestClientAutoConfiguration.class,
          HttpServiceClientAutoConfiguration.class,
          InternalApiClientAutoConfiguration.class))
      .withUserConfiguration(InternalApiClientAutoConfigurationTest.ClientsConfig.class)
      .withPropertyValues(
          "spring.http.serviceclient.payment.base-url=http://localhost:1",
          "spring.http.serviceclient.toss.base-url=http://localhost:1",
          "internal-api.groups=payment");

  @Test
  @DisplayName("키를 기본값 없이 ${...}로 적었는데 환경변수가 없으면 글자 그대로 들어오므로 시작하지 않는다")
  void failsWithUnresolvedPlaceholder() {
    runner.withPropertyValues("internal-api.key=${NOT_SET_INTERNAL_API_KEY_FOR_TEST}")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("키가 공백뿐이면 시작하지 않는다")
  void failsWithBlankKey() {
    runner.withPropertyValues("internal-api.key=   ")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("올바른 키면 정상 시작한다")
  void startsWithValidKey() {
    runner.withPropertyValues("internal-api.key=test-key")
        .run(context -> assertThat(context).hasNotFailed());
  }
}

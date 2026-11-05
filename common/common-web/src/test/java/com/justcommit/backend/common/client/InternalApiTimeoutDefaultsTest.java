package com.justcommit.backend.common.client;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class InternalApiTimeoutDefaultsTest {

  private final InternalApiTimeoutDefaults defaults = new InternalApiTimeoutDefaults();

  @Test
  void 내부_그룹에_타임아웃_기본값을_넣는다() {
    MockEnvironment environment = new MockEnvironment()
        .withProperty("internal-api.groups", "payment");

    defaults.postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty("spring.http.serviceclient.payment.connect-timeout")).isEqualTo("1s");
    assertThat(environment.getProperty("spring.http.serviceclient.payment.read-timeout")).isEqualTo("3s");
  }

  @Test
  void 서비스가_정한_타임아웃은_덮어쓰지_않는다() {
    MockEnvironment environment = new MockEnvironment()
        .withProperty("internal-api.groups", "payment")
        .withProperty("spring.http.serviceclient.payment.read-timeout", "10s");

    defaults.postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty("spring.http.serviceclient.payment.read-timeout")).isEqualTo("10s");
  }

  @Test
  void 내부_그룹이_아니면_기본값을_넣지_않는다() {
    MockEnvironment environment = new MockEnvironment()
        .withProperty("internal-api.groups", "payment");

    defaults.postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty("spring.http.serviceclient.toss.read-timeout")).isNull();
  }
}

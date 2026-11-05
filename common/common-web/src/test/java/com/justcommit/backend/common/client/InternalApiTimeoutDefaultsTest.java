package com.justcommit.backend.common.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class InternalApiTimeoutDefaultsTest {

  private final InternalApiTimeoutDefaults defaults = new InternalApiTimeoutDefaults();

  @Test
  @DisplayName("내부 그룹에 타임아웃 기본값을 넣는다")
  void addsDefaultTimeoutsToInternalGroup() {
    MockEnvironment environment = new MockEnvironment()
        .withProperty("internal-api.groups", "payment");

    defaults.postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty("spring.http.serviceclient.payment.connect-timeout")).isEqualTo("1s");
    assertThat(environment.getProperty("spring.http.serviceclient.payment.read-timeout")).isEqualTo("3s");
  }

  @Test
  @DisplayName("서비스가 정한 타임아웃은 덮어쓰지 않는다")
  void keepsServiceTimeout() {
    MockEnvironment environment = new MockEnvironment()
        .withProperty("internal-api.groups", "payment")
        .withProperty("spring.http.serviceclient.payment.read-timeout", "10s");

    defaults.postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty("spring.http.serviceclient.payment.read-timeout")).isEqualTo("10s");
  }

  @Test
  @DisplayName("내부 그룹이 아니면 기본값을 넣지 않는다")
  void skipsExternalGroup() {
    MockEnvironment environment = new MockEnvironment()
        .withProperty("internal-api.groups", "payment");

    defaults.postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty("spring.http.serviceclient.toss.read-timeout")).isNull();
  }
}

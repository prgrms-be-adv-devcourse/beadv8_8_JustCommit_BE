package com.justcommit.backend.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

class CommonLoggingEnvironmentPostProcessorTest {

  @Configuration(proxyBeanMethods = false)
  static class Empty {}

  private ConfigurableApplicationContext run(String... args) {
    return new SpringApplicationBuilder(Empty.class).web(WebApplicationType.NONE).run(args);
  }

  @Test
  void 공통_기본값이_들어간다() {
    try (var context = run("--spring.application.name=test-service")) {
      Environment env = context.getEnvironment();
      assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,prometheus");
      assertThat(env.getProperty("management.metrics.tags.application")).isEqualTo("test-service");
      assertThat(env.getProperty("management.metrics.distribution.percentiles-histogram[http.server.requests]"))
          .isEqualTo("true");
    }
  }

  @Test
  void 서비스_application_yml이_공통_기본값보다_이긴다() {
    // override-test.yml = 서비스의 application.yml 역할
    try (var context = run("--spring.config.name=override-test")) {
      assertThat(context.getEnvironment().getProperty("management.endpoints.web.exposure.include"))
          .isEqualTo("health");
    }
  }
}

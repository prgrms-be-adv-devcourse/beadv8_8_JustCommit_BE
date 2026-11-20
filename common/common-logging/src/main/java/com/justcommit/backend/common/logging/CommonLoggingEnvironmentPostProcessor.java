package com.justcommit.backend.common.logging;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * {@code common-logging.yml}을 가장 낮은 우선순위로 붙인다.
 * 서비스 application.yml · 환경변수에 같은 키가 있으면 그 값이 이긴다.
 * application.yml을 읽는 ConfigDataEnvironmentPostProcessor 다음에 돌아야 하므로 순서를 가장 뒤로 둔다.
 */
public class CommonLoggingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  static final String RESOURCE = "common-logging.yml";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
    try {
      List<PropertySource<?>> sources =
          new YamlPropertySourceLoader().load("common-logging", new ClassPathResource(RESOURCE));
      sources.forEach(environment.getPropertySources()::addLast);
    } catch (IOException e) {
      throw new UncheckedIOException(RESOURCE + "를 읽지 못함", e);
    }
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}

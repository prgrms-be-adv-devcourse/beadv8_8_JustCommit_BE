package com.justcommit.backend.common.client;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InternalApiTimeoutDefaults implements EnvironmentPostProcessor {

  static final String CONNECT_TIMEOUT = "1s";
  static final String READ_TIMEOUT = "3s";
  private static final String SOURCE_NAME = "internalApiTimeoutDefaults";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
    List<String> groups = Binder.get(environment)
        .bind("internal-api.groups", Bindable.listOf(String.class))
        .orElse(List.of());
    if (groups.isEmpty()) {
      return;
    }
    Map<String, Object> defaults = new HashMap<>();
    for (String group : groups) {
      defaults.put("spring.http.serviceclient." + group + ".connect-timeout", CONNECT_TIMEOUT);
      defaults.put("spring.http.serviceclient." + group + ".read-timeout", READ_TIMEOUT);
    }
    environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, defaults));
  }
}

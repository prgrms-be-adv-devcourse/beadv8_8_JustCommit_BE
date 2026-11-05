package com.justcommit.backend.common.client;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("internal-api")
public record InternalApiProperties(String key, List<String> groups) {

  public InternalApiProperties {
    groups = (groups == null) ? List.of() : List.copyOf(groups);
  }
}

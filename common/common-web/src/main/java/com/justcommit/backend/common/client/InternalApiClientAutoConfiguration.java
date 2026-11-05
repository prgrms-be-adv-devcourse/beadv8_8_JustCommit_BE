package com.justcommit.backend.common.client;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;

@AutoConfiguration
@EnableConfigurationProperties(InternalApiProperties.class)
public class InternalApiClientAutoConfiguration {

  @Bean
  RestClientHttpServiceGroupConfigurer internalApiKeyConfigurer(InternalApiProperties properties) {
    return groups -> {
      if (properties.groups().isEmpty()) {
        return;
      }
      if (properties.key() == null || properties.key().isBlank()) {
        throw new IllegalStateException("internal-api.groups가 있으면 internal-api.key가 필요합니다.");
      }
      groups.filterByName(properties.groups().toArray(String[]::new))
          .forEachClient((group, builder) -> builder.requestInterceptor((request, body, execution) -> {
            request.getHeaders().set(InternalApiHeaders.INTERNAL_KEY, properties.key());
            return execution.execute(request, body);
          })
                  .requestInterceptor(new AuthorizationForwardingInterceptor()));
    };
  }
}

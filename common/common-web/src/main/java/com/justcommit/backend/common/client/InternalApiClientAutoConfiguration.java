package com.justcommit.backend.common.client;

import com.justcommit.backend.common.client.exception.InternalApiUnavailableException;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;

import java.time.Duration;

@AutoConfiguration
@EnableConfigurationProperties(InternalApiProperties.class)
public class InternalApiClientAutoConfiguration {

  static final RetryConfig INTERNAL_RETRY = RetryConfig.custom()
      .maxAttempts(2)
      .waitDuration(Duration.ofMillis(200))
      .retryExceptions(InternalApiUnavailableException.class)
      .build();

  @Bean
  RestClientHttpServiceGroupConfigurer internalApiGroupConfigurer(InternalApiProperties properties) {
    return groups -> {
      if (properties.groups().isEmpty()) {
        return;
      }
      if (!isUsableKey(properties.key())) {
        throw new IllegalStateException("internal-api.groups가 있으면 internal-api.key가 필요합니다. (환경변수 INTERNAL_API_KEY 확인)");
      }
      groups.filterByName(properties.groups().toArray(String[]::new))
          .forEachClient((group, builder) -> builder
              .requestInterceptor(new InternalApiRetryInterceptor(Retry.of(group.name(), INTERNAL_RETRY)))
              .requestInterceptor(new InternalApiFailureInterceptor(group.name()))
              .requestInterceptor((request, body, execution) -> {
                request.getHeaders().set(InternalApiHeaders.INTERNAL_KEY, properties.key());
                return execution.execute(request, body);
              })
              .requestInterceptor(new AuthorizationForwardingInterceptor())
              .defaultStatusHandler(HttpStatusCode::isError, new InternalApiErrorHandler(group.name())));
    };
  }

  // 빈 값 · 공백 · 풀리지 않은 자리 표시자("${INTERNAL_API_KEY}")는 키 없음으로 취급
  // (받는 쪽 InternalKeyFilter와 같은 규칙)
  private static boolean isUsableKey(String key) {
    return key != null && !key.isBlank() && !key.startsWith("${");
  }
}

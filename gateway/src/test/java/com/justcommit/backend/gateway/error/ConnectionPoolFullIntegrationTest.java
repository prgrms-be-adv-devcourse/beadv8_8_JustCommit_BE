package com.justcommit.backend.gateway.error;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.justcommit.backend.gateway.GatewayIntegrationTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 연결 풀 자리가 없으면 1초 기다리고 503 (#661, docs/conventions/resilience.md) */
class ConnectionPoolFullIntegrationTest extends GatewayIntegrationTest {

  private static final String SLOW = "/api/v1/products/1";
  private static final String NEXT = "/api/v1/products/2";

  // 자리 1개로 줄여 두 번째 요청이 바로 자리 대기에 걸리게 한다 (운영 값은 100 · 1초)
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registerProperties(registry, Map.of(
        "spring.cloud.gateway.server.webflux.httpclient.pool.max-connections", () -> 1,
        "spring.cloud.gateway.server.webflux.httpclient.pool.acquire-timeout", () -> 300));
  }

  @Test
  @DisplayName("자리가 모두 찼으면 기다리다 503 SERVICE_UNAVAILABLE, 요청은 서비스에 가지 않음")
  void poolFull() {
    SERVICES.stubFor(get(urlEqualTo(SLOW))
        .willReturn(aResponse().withStatus(200).withBody("slow").withFixedDelay(3_000)));

    // 느린 요청이 하나뿐인 자리를 잡고 있는 동안
    Mono<EntityExchangeResult<byte[]>> slow = Mono.fromCallable(
            () -> client.get().uri(SLOW).exchange().expectBody().returnResult())
        .subscribeOn(Schedulers.boundedElastic());
    var slowResult = slow.toFuture();
    waitUntilRequested(SLOW);

    client.get().uri(NEXT).exchange()
        .expectStatus().isEqualTo(503)
        .expectBody()
        .jsonPath("$.status").isEqualTo(503)
        .jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");

    SERVICES.verify(0, getRequestedFor(urlEqualTo(NEXT)));
    assertThat(slowResult.join().getStatus().value()).isEqualTo(200);
  }

  private static void waitUntilRequested(String path) {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (SERVICES.findAll(getRequestedFor(urlEqualTo(path))).isEmpty()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError(path + " 요청이 서비스에 닿지 않음");
      }
      Thread.onSpinWait();
    }
  }
}

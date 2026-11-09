package com.justcommit.backend.gateway.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.justcommit.backend.gateway.GatewayIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Redis를 못 읽으면 토큰 폐기 여부를 모르니 막는다 (fail-closed, 노션 정책 정리 10.6 (추가)).
 * 이 클래스만 Redis 주소를 아무도 안 듣는 포트로 바꾼다.
 */
class RedisDownIntegrationTest extends GatewayIntegrationTest {

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    int closed = closedPort();
    registerProperties(registry, Map.of("spring.data.redis.port", () -> closed));
  }

  // 이 클래스에는 Redis가 없으니 비우기를 건너뜀
  @Override
  protected void prepareRedis() {
  }

  @Test
  @DisplayName("Redis를 못 읽음 → 보호 경로 503 SERVICE_UNAVAILABLE, 서비스에 안 감")
  void protectedPathIsBlocked() {
    client.get().uri("/api/v1/cart")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(1, "MEMBER"))
        .exchange()
        .expectStatus().isEqualTo(503)
        .expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");

    SERVICES.verify(0, anyRequestedFor(anyUrl()));
  }

  @Test
  @DisplayName("Redis를 못 읽어도 공개 경로(상품 상세)는 통과")
  void publicPathStillWorks() {
    client.get().uri("/api/v1/products/5").exchange().expectStatus().isOk();
    SERVICES.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/api/v1/products/5")));
  }
}

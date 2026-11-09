package com.justcommit.backend.gateway.error;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.http.Fault;
import com.justcommit.backend.gateway.GatewayIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec;

/** 서비스 장애 응답 (노션 정책 정리 10.8, docs/conventions/resilience.md): 503 · 504 · 502 모두 ApiResponse */
class ServiceFailureIntegrationTest extends GatewayIntegrationTest {

  // settlement만 아무도 안 듣는 포트로 → 연결 거절
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    int closed = closedPort();
    registerProperties(registry, Map.of("SETTLEMENT_SERVICE_URL", () -> "http://127.0.0.1:" + closed));
  }

  @Test
  @DisplayName("서비스가 꺼져 연결 못 함 → 503 SERVICE_UNAVAILABLE (다시 시도해도 안전)")
  void serviceDown() {
    expectError(authorizedGet("/api/v1/settlements"), 503, "SERVICE_UNAVAILABLE");
  }

  @Test
  @DisplayName("응답 시간 초과(택배 웹훅 5초) → 504 GATEWAY_TIMEOUT (결과 불명)")
  void responseTimeout() {
    SERVICES.stubFor(post(urlEqualTo("/api/v1/webhooks/courier"))
        .willReturn(aResponse().withStatus(200).withFixedDelay(6_000)));

    expectError(client.post().uri("/api/v1/webhooks/courier").exchange(), 504, "GATEWAY_TIMEOUT");
  }

  @Test
  @DisplayName("응답 받는 중 연결 끊김(reset) → 502 BAD_GATEWAY (결과 불명)")
  void connectionReset() {
    SERVICES.stubFor(post(urlEqualTo("/api/v1/auth/login"))
        .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

    expectError(client.post().uri("/api/v1/auth/login").exchange(), 502, "BAD_GATEWAY");
  }

  @Test
  @DisplayName("응답 없이 연결 닫힘 → 502 BAD_GATEWAY")
  void emptyResponse() {
    SERVICES.stubFor(post(urlEqualTo("/api/v1/auth/login"))
        .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

    expectError(client.post().uri("/api/v1/auth/login").exchange(), 502, "BAD_GATEWAY");
  }

  private ResponseSpec authorizedGet(String path) {
    return client.get().uri(path)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(1, "SELLER"))
        .exchange();
  }

  private static void expectError(ResponseSpec response, int status, String code) {
    response.expectStatus().isEqualTo(status)
        .expectBody()
        .jsonPath("$.status").isEqualTo(status)
        .jsonPath("$.code").isEqualTo(code);
  }
}

package com.justcommit.backend.gateway.forwarded;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.justcommit.backend.gateway.GatewayIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 믿지 않는 곳에서 온 X-Forwarded-*는 서비스에 넘기지 않는다 (주소를 꾸며 redirect_uri를 속이지 못하게, #550) */
class UntrustedProxyIntegrationTest extends GatewayIntegrationTest {

  // 테스트 요청(127.0.0.1)이 믿는 프록시 목록에 없게
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registerProperties(registry, Map.of("spring.cloud.gateway.server.webflux.trusted-proxies", () -> "192\\.0\\.2\\.1"));
  }

  @Test
  @DisplayName("믿지 않는 곳이 보낸 X-Forwarded-Host는 서비스에 도착하지 않는다")
  void dropsHeadersFromUntrustedSource() {
    client.get().uri("/oauth2/authorization/kakao")
        .header("X-Forwarded-Proto", "https")
        .header("X-Forwarded-Host", "evil.example.com")
        .exchange()
        .expectStatus().isOk();

    List<LoggedRequest> received = SERVICES.findAll(getRequestedFor(urlEqualTo("/oauth2/authorization/kakao")));
    assertThat(received).hasSize(1);
    String forwardedHost = received.getFirst().getHeader("X-Forwarded-Host");
    assertThat(forwardedHost == null ? "" : forwardedHost).doesNotContain("evil.example.com");
  }
}

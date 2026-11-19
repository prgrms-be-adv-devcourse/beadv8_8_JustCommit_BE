package com.justcommit.backend.gateway.forwarded;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.justcommit.backend.gateway.GatewayIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Nginx가 붙인 X-Forwarded-*를 서비스까지 넘긴다 (#550).
 * 지워지면 member가 카카오 redirect_uri를 내부 주소(http://10.0.7.76:8081/...)로 만들어 KOE006이 난다.
 * 테스트 요청은 127.0.0.1에서 오므로 운영 설정(trusted-proxies: 10.x · 127.0.0.1)이 그대로 적용된다.
 */
class ForwardedHeadersIntegrationTest extends GatewayIntegrationTest {

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registerProperties(registry, Map.of());
  }

  @Test
  @DisplayName("믿는 프록시(Nginx)가 보낸 X-Forwarded-Proto · Host · Port가 서비스에 원래 값으로 도착한다")
  void forwardsHeadersFromTrustedProxy() {
    client.get().uri("/oauth2/authorization/kakao")
        .header("X-Forwarded-Proto", "https")
        .header("X-Forwarded-Host", "replant.kro.kr")
        .header("X-Forwarded-Port", "443")
        .exchange()
        .expectStatus().isOk();

    // Gateway는 자기 값을 뒤에 덧붙인다(https,http). 서비스(ForwardedHeaderFilter)는 맨 앞 값을 쓴다
    SERVICES.verify(getRequestedFor(urlEqualTo("/oauth2/authorization/kakao"))
        .withHeader("X-Forwarded-Proto", matching("^https(,.*)?$"))
        .withHeader("X-Forwarded-Host", matching("^replant\\.kro\\.kr(,.*)?$"))
        .withHeader("X-Forwarded-Port", matching("^443(,.*)?$")));
  }
}

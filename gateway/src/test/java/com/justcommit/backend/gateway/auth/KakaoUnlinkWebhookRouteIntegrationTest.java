package com.justcommit.backend.gateway.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.justcommit.backend.gateway.GatewayIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 카카오 연결 해제 웹훅 라우트 (#422). market 라우트(/api/v1/webhooks/**)보다 앞에서 member로 보내고,
 * JWT 없이 통과(카카오가 보낸 KakaoAK 헤더는 member가 확인)하며 응답 타임아웃은 5초다.
 */
class KakaoUnlinkWebhookRouteIntegrationTest extends GatewayIntegrationTest {

  private static final String PATH = "/api/v1/webhooks/kakao/unlink";
  private static final String KAKAO_AUTHORIZATION = "KakaoAK test-admin-key";

  // market만 아무도 안 듣는 포트로 → market 라우트로 가면 503, member로 가면 WireMock 응답
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    int closed = closedPort();
    registerProperties(registry, Map.of("MARKET_SERVICE_URL", () -> "http://127.0.0.1:" + closed));
  }

  @Test
  @DisplayName("[#422] GET 쿼리: JWT 없이 member로 전달되고, KakaoAK 헤더 · 쿼리가 그대로 간다")
  void getRoutesToMemberWithoutJwt() {
    client.get().uri(PATH + "?app_id=1234567&user_id=9400000001&referrer_type=UNLINK_FROM_APPS")
        .header(HttpHeaders.AUTHORIZATION, KAKAO_AUTHORIZATION)
        .exchange()
        .expectStatus().isOk();

    SERVICES.verify(getRequestedFor(urlPathEqualTo(PATH))
        .withHeader(HttpHeaders.AUTHORIZATION, equalTo(KAKAO_AUTHORIZATION))
        .withQueryParam("user_id", equalTo("9400000001")));
  }

  @Test
  @DisplayName("[#422] POST 폼: JWT 없이 member로 전달되고, 본문이 그대로 간다")
  void postRoutesToMemberWithoutJwt() {
    client.post().uri(PATH)
        .header(HttpHeaders.AUTHORIZATION, KAKAO_AUTHORIZATION)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .bodyValue("app_id=1234567&user_id=9400000002&referrer_type=ACCOUNT_DELETE")
        .exchange()
        .expectStatus().isOk();

    SERVICES.verify(postRequestedFor(urlPathEqualTo(PATH))
        .withHeader(HttpHeaders.AUTHORIZATION, equalTo(KAKAO_AUTHORIZATION))
        .withRequestBody(containing("user_id=9400000002")));
  }

  @Test
  @DisplayName("[#422] member 응답이 5초를 넘으면 504 (이 경로만 5초, 카카오의 3초 요구와는 별개)")
  void routeTimeoutIsFiveSeconds() {
    SERVICES.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200).withFixedDelay(6_000)));

    client.get().uri(PATH + "?app_id=1234567&user_id=9400000003&referrer_type=UNLINK_FROM_APPS")
        .header(HttpHeaders.AUTHORIZATION, KAKAO_AUTHORIZATION)
        .exchange()
        .expectStatus().isEqualTo(504);
  }

  @Test
  @DisplayName("[#422] 택배 웹훅은 그대로 market으로 간다 (새 라우트가 다른 웹훅을 가로채지 않음)")
  void courierWebhookStillGoesToMarket() {
    // market은 닫힌 포트라 market 라우트로 가면 연결 실패 503
    client.post().uri("/api/v1/webhooks/courier")
        .exchange()
        .expectStatus().isEqualTo(503);
  }
}

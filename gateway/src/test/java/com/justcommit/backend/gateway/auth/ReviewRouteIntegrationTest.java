package com.justcommit.backend.gateway.auth;

import com.justcommit.backend.gateway.GatewayIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * 리뷰 라우트 · 공개 경로 (#581). /api/v1/reviews/** 는 market으로 가고,
 * 토큰 없이 통과하는 것은 판매자 리뷰 목록 · 집계(GET reviews · GET reviews/summary) 두 개뿐이다.
 * market 응답 내용(목록 · 집계 · 작성 결과)은 보지 않는다: 각 API를 구현한 Task에서 확인한다.
 */
class ReviewRouteIntegrationTest extends GatewayIntegrationTest {

  // market만 WireMock으로 두고 나머지 서비스는 아무도 안 듣는 포트로 → market이 아닌 곳으로 가면 503
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    int closed = closedPort();
    Map<String, Supplier<Object>> overrides = new LinkedHashMap<>();
    for (String service : new String[] {"MEMBER", "PRODUCT", "PAYMENT", "SETTLEMENT", "NOTIFICATION", "AI"}) {
      overrides.put(service + "_SERVICE_URL", () -> "http://127.0.0.1:" + closed);
    }
    registerProperties(registry, overrides);
  }

  @Test
  @DisplayName("[#581] 토큰 없이 판매자 리뷰 목록 · 집계는 market까지 가고, 쿼리가 그대로 간다")
  void sellerReviewListAndSummaryArePublic() {
    request(HttpMethod.GET, "/api/v1/reviews?sellerId=7&page=0&size=10", null).expectStatus().isOk();
    request(HttpMethod.GET, "/api/v1/reviews/summary?sellerId=7", null).expectStatus().isOk();

    SERVICES.verify(getRequestedFor(urlPathEqualTo("/api/v1/reviews"))
      .withQueryParam("sellerId", equalTo("7"))
      .withQueryParam("page", equalTo("0")));
    SERVICES.verify(getRequestedFor(urlPathEqualTo("/api/v1/reviews/summary"))
      .withQueryParam("sellerId", equalTo("7")));
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({
    "GET, /api/v1/reviews/me",
    "GET, /api/v1/reviews/writable",
    "POST, /api/v1/reviews",
    "DELETE, /api/v1/reviews/1"
  })
  @DisplayName("[#581] 토큰 없이 내 리뷰 · 작성 가능 목록 · 작성 · 삭제는 401 UNAUTHORIZED이고 market에 가지 않는다")
  void loginRequiredReviewApisRejectAnonymous(HttpMethod method, String path) {
    expectError(request(method, path, null), 401, "UNAUTHORIZED");

    SERVICES.verify(0, anyRequestedFor(anyUrl()));
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({
    "GET, /api/v1/reviews/me",
    "GET, /api/v1/reviews/writable",
    "POST, /api/v1/reviews",
    "DELETE, /api/v1/reviews/1"
  })
  @DisplayName("[#581] 로그인하면 내 리뷰 · 작성 가능 목록 · 작성 · 삭제가 토큰을 그대로 실어 market까지 간다")
  void loginRequiredReviewApisRouteToMarketWithToken(HttpMethod method, String path) {
    String token = accessToken(1, "MEMBER");

    request(method, path, token).expectStatus().isOk();

    SERVICES.verify(1, anyRequestedFor(urlPathEqualTo(path))
      .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + token)));
  }

  // 공개는 두 경로의 GET만: 같은 경로의 다른 메서드, 아래 경로, 끝 슬래시는 공개가 아니다
  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({
    "POST, /api/v1/reviews/summary",
    "DELETE, /api/v1/reviews",
    "GET, /api/v1/reviews/",
    "GET, /api/v1/reviews/summary/7",
    "GET, /api/v1/reviews/7"
  })
  @DisplayName("[#581] 공개 경로와 비슷한 다른 메서드 · 하위 경로 · 끝 슬래시는 토큰이 없으면 401이다")
  void onlyExactPublicPathsAreOpen(HttpMethod method, String path) {
    expectError(request(method, path, null), 401, "UNAUTHORIZED");

    SERVICES.verify(0, anyRequestedFor(anyUrl()));
  }

  private ResponseSpec request(HttpMethod method, String path, String token) {
    var request = client.method(method).uri(path);
    if (token != null) {
      request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    return request.exchange();
  }

  private static void expectError(ResponseSpec response, int status, String code) {
    response.expectStatus().isEqualTo(status)
      .expectBody()
      .jsonPath("$.status").isEqualTo(status)
      .jsonPath("$.code").isEqualTo(code)
      .jsonPath("$.message").isNotEmpty();
  }
}

package com.justcommit.backend.gateway.ratelimit;

import com.justcommit.backend.gateway.GatewayIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec;

/** 요청 수 제한(docs/services/gateway.md, IP당 1분 5회)과 CORS(Gateway에서만) */
class RateLimitAndCorsIntegrationTest extends GatewayIntegrationTest {

  private static final String ALLOWED_ORIGIN = "http://localhost:5173";

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registerProperties(registry, Map.of());
  }

  @Test
  @DisplayName("로그인은 IP당 1분 5회 → 6번째 429 TOO_MANY_REQUESTS, 다른 IP는 따로 셈")
  void loginLimitPerIp() {
    for (int i = 0; i < 5; i++) {
      post("/api/v1/auth/login", "10.0.0.1").expectStatus().isOk();
    }
    post("/api/v1/auth/login", "10.0.0.1")
        .expectStatus().isEqualTo(429)
        .expectBody()
        .jsonPath("$.status").isEqualTo(429)
        .jsonPath("$.code").isEqualTo("TOO_MANY_REQUESTS");

    post("/api/v1/auth/login", "10.0.0.2").expectStatus().isOk();
  }

  @Test
  @DisplayName("인증번호 발송은 로그인과 따로 IP당 1분 5회, 로그아웃은 제한 없음")
  void emailSendLimitIsSeparate() {
    for (int i = 0; i < 5; i++) {
      post("/api/v1/auth/login", "10.0.0.3").expectStatus().isOk();
    }
    for (int i = 0; i < 5; i++) {
      post("/api/v1/auth/email/send", "10.0.0.3").expectStatus().isOk();
    }
    post("/api/v1/auth/email/send", "10.0.0.3").expectStatus().isEqualTo(429);
    for (int i = 0; i < 7; i++) {
      post("/api/v1/auth/logout", "10.0.0.3").expectStatus().isOk();
    }
  }

  @Test
  @DisplayName("CORS preflight: 허용 출처는 200 + 쿠키 허용 헤더, 허용 안 된 출처는 403")
  void corsPreflight() {
    preflight(ALLOWED_ORIGIN)
        .expectStatus().isOk()
        .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN)
        .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");

    preflight("https://evil.example").expectStatus().isForbidden();
  }

  @Test
  @DisplayName("CORS: 401 응답에도 허용 헤더가 붙어 브라우저가 에러 본문(code)을 읽을 수 있음")
  void corsHeadersOnErrorResponse() {
    client.get().uri("/api/v1/cart")
        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
        .exchange()
        .expectStatus().isUnauthorized()
        .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN);
  }

  private ResponseSpec post(String path, String clientIp) {
    return client.post().uri(path).header("X-Forwarded-For", clientIp).exchange();
  }

  private ResponseSpec preflight(String origin) {
    return client.method(HttpMethod.OPTIONS).uri("/api/v1/cart")
        .header(HttpHeaders.ORIGIN, origin)
        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
        .exchange();
  }
}

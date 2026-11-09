package com.justcommit.backend.gateway.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.justcommit.backend.common.security.TokenHash;
import com.justcommit.backend.gateway.GatewayIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec;

/** Gateway 1차 토큰 검사 (docs/conventions/auth.md, 시나리오 S-A02 ~ S-A05 · S-A08) */
class JwtAuthenticationIntegrationTest extends GatewayIntegrationTest {

  private static final String PROTECTED = "/api/v1/cart";

  @Test
  @DisplayName("정상 Access 토큰 → 서비스까지 가고, 토큰은 바꾸지 않고 그대로 전달")
  void validTokenIsForwardedUnchanged() {
    String token = accessToken(1, "MEMBER");

    get(PROTECTED, token).expectStatus().isOk();

    SERVICES.verify(1, getRequestedFor(urlEqualTo(PROTECTED))
        .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + token)));
  }

  @Test
  @DisplayName("토큰 없음 → 401 UNAUTHORIZED, 서비스에 안 감")
  void noToken() {
    expectError(get(PROTECTED, null), 401, "UNAUTHORIZED");
    SERVICES.verify(0, anyRequestedFor(anyUrl()));
  }

  @Test
  @DisplayName("[S-A02] 만료 토큰 → Gateway 401 TOKEN_EXPIRED, 서비스에 안 감")
  void expiredToken() {
    expectError(get(PROTECTED, expiredToken(1)), 401, "TOKEN_EXPIRED");
    SERVICES.verify(0, anyRequestedFor(anyUrl()));
  }

  @Test
  @DisplayName("[S-A03] 다른 키로 서명한 위조 토큰 → Gateway 401 INVALID_TOKEN (서비스 직접 호출 쪽은 common-security 테스트)")
  void forgedToken() {
    Instant now = Instant.now();
    String forged = token(OTHER_KEY_PAIR.getPrivate(), 1, "ADMIN", "ACCESS", now.minusSeconds(60), now.plusSeconds(600));

    expectError(get(PROTECTED, forged), 401, "INVALID_TOKEN");
    SERVICES.verify(0, anyRequestedFor(anyUrl()));
  }

  @Test
  @DisplayName("가입 토큰(type SIGNUP)은 Access로 못 씀 → 401 INVALID_TOKEN")
  void signupTokenIsNotAccess() {
    Instant now = Instant.now();
    String signup = token(KEY_PAIR.getPrivate(), 1, "MEMBER", "SIGNUP", now.minusSeconds(60), now.plusSeconds(600));

    expectError(get(PROTECTED, signup), 401, "INVALID_TOKEN");
  }

  @Test
  @DisplayName("[S-A04] 로그아웃한 토큰(블랙리스트) → 401 INVALID_TOKEN, 같은 회원의 다른 기기 토큰은 통과")
  void blacklistedToken() {
    Instant now = Instant.now();
    String loggedOut = token(KEY_PAIR.getPrivate(), 1, "MEMBER", "ACCESS", now.minusSeconds(120), now.plusSeconds(600));
    String otherDevice = token(KEY_PAIR.getPrivate(), 1, "MEMBER", "ACCESS", now.minusSeconds(60), now.plusSeconds(600));
    redis.opsForValue().set("auth:blacklist:" + TokenHash.sha256Hex(loggedOut), "1", Duration.ofMinutes(1)).block();

    expectError(get(PROTECTED, loggedOut), 401, "INVALID_TOKEN");
    get(PROTECTED, otherDevice).expectStatus().isOk();
  }

  @Test
  @DisplayName("[S-A08] 비밀번호 변경 · 탈퇴 → iat <= 끊은 시각인 토큰 401, 끊은 뒤 발급된 토큰 · 다른 회원은 통과")
  void revokedMember() {
    Instant cutAt = Instant.now();
    Instant exp = cutAt.plusSeconds(600);
    String before = token(KEY_PAIR.getPrivate(), 1, "MEMBER", "ACCESS", cutAt.minusSeconds(60), exp);
    String sameSecond = token(KEY_PAIR.getPrivate(), 1, "MEMBER", "ACCESS", cutAt, exp);
    String after = token(KEY_PAIR.getPrivate(), 1, "MEMBER", "ACCESS", cutAt.plusSeconds(1), exp);
    String otherMember = token(KEY_PAIR.getPrivate(), 2, "MEMBER", "ACCESS", cutAt.minusSeconds(60), exp);
    redis.opsForValue()
        .set("auth:revoked-member:1", String.valueOf(cutAt.getEpochSecond()), Duration.ofMinutes(30)).block();

    expectError(get(PROTECTED, before), 401, "INVALID_TOKEN");
    expectError(get(PROTECTED, sameSecond), 401, "INVALID_TOKEN");
    get(PROTECTED, after).expectStatus().isOk();
    get(PROTECTED, otherMember).expectStatus().isOk();
  }

  @Test
  @DisplayName("[S-A05] 외부에서 /internal/** 호출 → 라우팅 없음 404 RESOURCE_NOT_FOUND, 서비스에 안 감")
  void internalPathIsNotRouted() {
    expectError(get("/internal/v1/orders/1", accessToken(1, "ADMIN")), 404, "RESOURCE_NOT_FOUND");
    SERVICES.verify(0, anyRequestedFor(anyUrl()));
  }

  @Test
  @DisplayName("ADMIN 경로는 ADMIN만 → MEMBER 403 FORBIDDEN, ADMIN 통과")
  void adminPath() {
    String path = "/api/v1/admin/imageReviews";

    expectError(get(path, accessToken(1, "MEMBER")), 403, "FORBIDDEN");
    get(path, accessToken(9, "ADMIN")).expectStatus().isOk();
  }

  @Test
  @DisplayName("공개 경로: 상품 상세 · 품종은 토큰 없이 통과, products/me(SELLER)는 공개 아님 → 401")
  void publicPaths() {
    get("/api/v1/products/5", null).expectStatus().isOk();
    get("/api/v1/species", null).expectStatus().isOk();
    expectError(get("/api/v1/products/me", null), 401, "UNAUTHORIZED");
  }

  @Test
  @DisplayName("로그아웃 · 재발급은 만료 토큰이어도 member까지 감 (/api/v1/auth/** 토큰 검사 제외)")
  void logoutAndReissueWithExpiredToken() {
    String expired = expiredToken(1);

    client.post().uri("/api/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
        .exchange().expectStatus().isOk();
    client.post().uri("/api/v1/auth/reissue").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
        .exchange().expectStatus().isOk();
  }

  private static String expiredToken(long memberId) {
    Instant now = Instant.now();
    return token(KEY_PAIR.getPrivate(), memberId, "MEMBER", "ACCESS", now.minusSeconds(3600), now.minusSeconds(60));
  }

  private ResponseSpec get(String path, String token) {
    var request = client.get().uri(path);
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

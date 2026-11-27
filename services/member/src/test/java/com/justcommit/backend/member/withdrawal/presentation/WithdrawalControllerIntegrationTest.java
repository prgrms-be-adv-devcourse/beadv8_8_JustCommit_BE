package com.justcommit.backend.member.withdrawal.presentation;

import com.justcommit.backend.member.support.MemberIntegrationTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.justcommit.backend.member.auth.application.RevocationOutcomeUnknownException;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * 회원 탈퇴 HTTP 계약 (#407, docs/reference/external-apis.md 「회원 탈퇴」)을 실제 요청으로 확인한다.
 * 업무 흐름의 세부 경우는 WithdrawalServiceIntegrationTest, 카카오 재인증은 WithdrawalVerificationIntegrationTest
 * (메일 재발송 제한 429 + Retry-After 포함)에서 확인한다. 여기서는 응답 모양 · 쿠키 · 헤더 · 상태 코드만 본다.
 * Gateway를 거치지 않으므로, 탈퇴 뒤 기존 Access의 Gateway 차단(401)은 이 테스트 범위가 아니다 (member는 상태 검사로 404).
 */
class WithdrawalControllerIntegrationTest extends MemberIntegrationTest {

  // 베이스의 공유 WireMock (테스트마다 응답 설정 · 요청 기록 초기화)
  private static final WireMockServer SERVICES = EXTERNAL_API;

  private static final AtomicInteger SEQ = new AtomicInteger();
  private static final String PASSWORD = "plant1234";
  private static final String WITHDRAWAL = "/api/v1/users/me/withdrawal";
  private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");
  private static final Pattern REFRESH_COOKIE = Pattern.compile("refreshToken=([^;]+)");
  private static final String PASS = """
      {"hasWithdrawalBlocker": false, "blockingReasons": [], "details": {}}""";
  private static final String BLOCK = """
      {"hasWithdrawalBlocker": true, "blockingReasons": ["ACTIVE_BUYER_ORDER"], "details": {"activeBuyerOrderCount": 2}}""";

  @Value("${local.server.port}")
  private int port;

  @Autowired MemberRepository memberRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired StringRedisTemplate redisTemplate;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from member_withdrawal_record");
    jdbcTemplate.update("delete from member_token_revocation");
    jdbcTemplate.update("delete from outbox_event");
    jdbcTemplate.update("delete from seller");
    jdbcTemplate.update("delete from address");
    jdbcTemplate.update("delete from member");
    try (var connection = redisTemplate.getRequiredConnectionFactory().getConnection()) {
      connection.serverCommands().flushAll();
    }
  }

  @Test
  @DisplayName("로그인 없이 호출하면 사전 확인 · 탈퇴 모두 401")
  void requiresLogin() throws Exception {
    assertThat(send("GET", WITHDRAWAL, null, null).statusCode()).isEqualTo(401);
    assertThat(send("POST", WITHDRAWAL, null, passwordBody(PASSWORD)).statusCode()).isEqualTo(401);
  }

  @Test
  @DisplayName("사전 확인: 200 status · blockers(서비스 · 사유 · details), 캐시하지 않음(no-store)")
  void checkEligibility() throws Exception {
    Login login = login(saveMember());

    stubChecks(PASS, PASS, PASS);
    HttpResponse<String> allowed = send("GET", WITHDRAWAL, login.accessToken(), null);
    assertThat(allowed.statusCode()).as(allowed.body()).isEqualTo(200);
    assertThat(allowed.body()).contains("\"status\":\"ALLOWED\"", "\"blockers\":[]");
    assertThat(allowed.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));

    stubChecks(BLOCK, PASS, PASS);
    HttpResponse<String> blocked = send("GET", WITHDRAWAL, login.accessToken(), null);
    assertThat(blocked.statusCode()).isEqualTo(200);
    assertThat(blocked.body()).contains("\"status\":\"BLOCKED\"", "\"service\":\"market\"",
        "\"ACTIVE_BUYER_ORDER\"", "\"activeBuyerOrderCount\":2");
  }

  @Test
  @DisplayName("사전 확인 실패: 503 WITHDRAWAL_CHECK_UNAVAILABLE, 일부 서비스의 차단 사유도 응답에 없음")
  void checkUnavailable() throws Exception {
    Login login = login(saveMember());
    stubChecks(BLOCK, null, PASS);

    HttpResponse<String> response = send("GET", WITHDRAWAL, login.accessToken(), null);

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).contains("\"code\":\"WITHDRAWAL_CHECK_UNAVAILABLE\"").doesNotContain("ACTIVE_BUYER_ORDER");
  }

  @Test
  @DisplayName("탈퇴 성공: 200 · revocationCompleted, Refresh 쿠키 만료(이름 · Path · Max-Age · HttpOnly), no-store. 그 뒤 로그인 · 재발급 401, 내 정보 404")
  void withdraw() throws Exception {
    Member member = saveMember();
    Login login = login(member);
    stubChecks(PASS, PASS, PASS);

    HttpResponse<String> response = send("POST", WITHDRAWAL, login.accessToken(), passwordBody(PASSWORD));

    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(response.body()).contains("\"revocationCompleted\":true");
    assertThat(response.headers().firstValue("Set-Cookie")).hasValueSatisfying(cookie -> assertThat(cookie)
        .contains("refreshToken=", "Max-Age=0", "Path=/api/v1/auth", "HttpOnly"));
    assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));

    assertThat(send("POST", "/api/v1/auth/login", null, loginBody(member.getEmail())).statusCode()).isEqualTo(401);
    assertThat(reissue(login.refreshToken()).statusCode()).isEqualTo(401);
    assertThat(send("GET", "/api/v1/users/me", login.accessToken(), null).statusCode()).isEqualTo(404);
  }

  @Test
  @DisplayName("폐기 기록이 늦어져도(PENDING) 탈퇴는 커밋돼 200 · revocationCompleted=false, 쿠키 만료")
  void withdrawPending() throws Exception {
    Member member = saveMember();
    Login login = login(member);
    stubChecks(PASS, PASS, PASS);
    doThrow(new RedisConnectionFailureException("Redis 기록 실패 (테스트)"))
        .when(revokedMemberRedisStore).recordCutoff(anyLong(), any(), any());

    HttpResponse<String> response = send("POST", WITHDRAWAL, login.accessToken(), passwordBody(PASSWORD));

    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(response.body()).contains("\"revocationCompleted\":false");
    assertThat(response.headers().firstValue("Set-Cookie")).hasValueSatisfying(c -> assertThat(c).contains("Max-Age=0"));
    // 폐기 기록(Redis)만 늦어졌고 탈퇴와 MemberWithdrawn은 함께 커밋돼 있음 (#408)
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from outbox_event where event_type = 'MemberWithdrawn' and aggregate_id = ?",
        Long.class, String.valueOf(member.getId()))).isEqualTo(1);
  }

  @Test
  @DisplayName("재확인 값이 없거나 둘 다 보내면 400 BAD_REQUEST_DATA, 쿠키는 그대로")
  void badReconfirmation() throws Exception {
    Login login = login(saveMember());

    HttpResponse<String> none = send("POST", WITHDRAWAL, login.accessToken(), "{}");
    HttpResponse<String> both = send("POST", WITHDRAWAL, login.accessToken(),
        "{\"password\":\"" + PASSWORD + "\",\"withdrawalToken\":\"token\"}");

    assertThat(none.statusCode()).isEqualTo(400);
    assertThat(none.body()).contains("\"code\":\"BAD_REQUEST_DATA\"");
    assertThat(both.statusCode()).isEqualTo(400);
    assertThat(none.headers().firstValue("Set-Cookie")).isEmpty();
  }

  @Test
  @DisplayName("비밀번호를 다섯 번 틀린 뒤에는 429 PASSWORD_CHANGE_LOCKED + Retry-After")
  void passwordLocked() throws Exception {
    Login login = login(saveMember());
    for (int i = 0; i < 5; i++) {
      assertThat(send("POST", WITHDRAWAL, login.accessToken(), passwordBody("wrong-password")).statusCode()).isEqualTo(400);
    }

    HttpResponse<String> response = send("POST", WITHDRAWAL, login.accessToken(), passwordBody(PASSWORD));

    assertThat(response.statusCode()).isEqualTo(429);
    assertThat(response.body()).contains("\"code\":\"PASSWORD_CHANGE_LOCKED\"");
    assertThat(response.headers().firstValue("Retry-After")).isPresent();
  }

  @Test
  @DisplayName("커밋 결과 불명이면 503 RESULT_UNKNOWN, 쿠키 만료 헤더 없음 (자동 재요청하지 않도록)")
  void resultUnknown() throws Exception {
    Member member = saveMember();
    Login login = login(member);
    stubChecks(PASS, PASS, PASS);
    doThrow(new RevocationOutcomeUnknownException(member.getId(), new IllegalStateException("커밋 결과 불명 (테스트)")))
        .when(tokenRevocationService).revoke(anyLong(), any(), any());

    HttpResponse<String> response = send("POST", WITHDRAWAL, login.accessToken(), passwordBody(PASSWORD));

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).contains("\"code\":\"RESULT_UNKNOWN\"");
    assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
  }

  // ===== 도우미 =====

  private record Login(String accessToken, String refreshToken) {
  }

  private Login login(Member member) throws Exception {
    HttpResponse<String> response = send("POST", "/api/v1/auth/login", null, loginBody(member.getEmail()));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    Matcher access = ACCESS_TOKEN.matcher(response.body());
    Matcher refresh = REFRESH_COOKIE.matcher(response.headers().firstValue("Set-Cookie").orElse(""));
    assertThat(access.find()).isTrue();
    assertThat(refresh.find()).isTrue();
    return new Login(access.group(1), refresh.group(1));
  }

  private HttpResponse<String> reissue(String refreshToken) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/reissue"))
        .header("Cookie", "refreshToken=" + refreshToken)
        .POST(HttpRequest.BodyPublishers.noBody())
        .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> send(String method, String path, String accessToken, String json) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json")
        .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
    if (accessToken != null) {
      request.header("Authorization", "Bearer " + accessToken);
    }
    return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String passwordBody(String password) {
    return "{\"password\":\"" + password + "\"}";
  }

  private static String loginBody(String email) {
    return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
  }

  // market · payment · settlement 순서. null이면 그 서비스는 500(확인 실패)
  private void stubChecks(String market, String payment, String settlement) {
    String[] paths = {"orders", "payments", "settlements"};
    String[] bodies = {market, payment, settlement};
    for (int i = 0; i < paths.length; i++) {
      SERVICES.stubFor(get(urlPathMatching("/internal/v1/" + paths[i] + "/members/\\d+/withdrawalCheck"))
          .willReturn(bodies[i] == null
              ? aResponse().withStatus(500)
              : aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                  .withBody("{\"status\": 200, \"code\": \"OK\", \"message\": \"ok\", \"data\": " + bodies[i] + "}")));
    }
  }

  private Member saveMember() {
    int n = SEQ.incrementAndGet();
    return memberRepository.save(Member.createLocal("leave" + n + "@test.com", passwordEncoder.encode(PASSWORD),
        "떠남" + n, String.format("012%08d", n), "004", "v1:test-account", null, "홍길동"));
  }
}

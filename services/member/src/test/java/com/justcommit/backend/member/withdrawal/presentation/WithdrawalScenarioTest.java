package com.justcommit.backend.member.withdrawal.presentation;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.justcommit.backend.common.security.JwtVerifier;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.infrastructure.crypto.MemberHmac;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회원 탈퇴 시나리오 (docs/testing/scenarios.md). 세부 분기는 WithdrawalServiceIntegrationTest ·
 * WithdrawalControllerIntegrationTest에 있고, 여기서는 시나리오 ID마다 실제 HTTP 요청으로 끝과 끝을 확인한다.
 * - S-A08: 탈퇴가 Gateway가 읽는 회원 단위 폐기 키를 실제로 기록하는지 확인한다. 그 키로 Access를 401로 막는 것은
 *   Gateway JwtAuthenticationIntegrationTest의 [S-A08]이, 옛 Refresh 삭제가 실패해도 인증 버전 비교로 막히는 것은
 *   TokenRevocationServiceIntegrationTest가 확인한다. 세 테스트를 합쳐 S-A08의 탈퇴 경로가 이어진다
 * - S-B04: 3곳 병렬 확인 중 하나라도 실패하면 탈퇴를 막는다 (Fail-closed). 병렬 호출 · UNAVAILABLE 판정 자체는
 *   WithdrawalCheckServiceIntegrationTest가 확인한다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class WithdrawalScenarioTest {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

  @RegisterExtension
  static final WireMockExtension SERVICES = WireMockExtension.newInstance()
      .options(wireMockConfig().dynamicPort())
      .build();

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final AtomicInteger SEQ = new AtomicInteger();
  private static final String PASSWORD = "plant1234";
  private static final String WITHDRAWAL = "/api/v1/users/me/withdrawal";
  private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");
  private static final Pattern REFRESH_COOKIE = Pattern.compile("refreshToken=([^;]+)");
  private static final String PASS = """
      {"hasWithdrawalBlocker": false, "blockingReasons": [], "details": {}}""";
  private static final String BLOCK = """
      {"hasWithdrawalBlocker": true, "blockingReasons": ["ACTIVE_BUYER_ORDER"], "details": {}}""";

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("spring.http.serviceclient.market.base-url", SERVICES::baseUrl);
    registry.add("spring.http.serviceclient.payment.base-url", SERVICES::baseUrl);
    registry.add("spring.http.serviceclient.settlement.base-url", SERVICES::baseUrl);
  }

  @Value("${local.server.port}")
  private int port;

  @Autowired MemberRepository memberRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired MemberHmac memberHmac;
  @Autowired JwtVerifier jwtVerifier;
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
  @DisplayName("[S-A08] 탈퇴 → 폐기 시각이 기록되고 기존 Access의 iat <= 끊은 시각(Gateway 401 대상), 옛 Refresh 재발급 401")
  void withdrawalRevokesTokens() throws Exception {
    Member member = saveMember();
    Login login = login(member);
    long issuedAt = jwtVerifier.verifyAccessToken(login.accessToken()).issuedAt();
    stubChecks(PASS, PASS, PASS);

    HttpResponse<String> response = send("POST", WITHDRAWAL, login.accessToken(), passwordBody(PASSWORD));

    // 폐기 시각이 Redis에 기록된 경우(COMPLETED)에만 키를 확인한다. PENDING이면 재처리 스케줄러가 나중에 기록한다
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(response.body()).contains("\"revocationCompleted\":true");
    String cutoff = redisTemplate.opsForValue().get("auth:revoked-member:" + member.getId());
    assertThat(cutoff).isNotNull();
    // Gateway TokenRevocationChecker와 같은 방식으로 읽음 (Long.parseLong) → iat <= 끊은 시각이면 거절
    assertThat(issuedAt).isLessThanOrEqualTo(Long.parseLong(cutoff));

    HttpResponse<String> reissued = reissue(login.refreshToken());
    assertThat(reissued.statusCode()).isEqualTo(401);
    assertThat(reissued.body()).contains("\"code\":\"INVALID_REFRESH_TOKEN\"");
  }

  @Test
  @DisplayName("[S-B04] 탈퇴 확인 3곳 중 하나라도 실패(UNAVAILABLE)하면 탈퇴를 막음 (Fail-closed): 503, 다른 곳의 차단 사유도 노출 없음, 회원 · 기록 · 폐기 작업 그대로")
  void withdrawalBlockedWhenCheckUnavailable() throws Exception {
    Member member = saveMember();
    Login login = login(member);
    Member before = memberRepository.findById(member.getId()).orElseThrow();
    // market은 차단, payment는 실패: 실패가 차단보다 우선해 UNAVAILABLE
    stubChecks(BLOCK, null, PASS);

    HttpResponse<String> response = send("POST", WITHDRAWAL, login.accessToken(), passwordBody(PASSWORD));

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).contains("\"code\":\"WITHDRAWAL_CHECK_UNAVAILABLE\"").doesNotContain("ACTIVE_BUYER_ORDER");
    assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();

    Member after = memberRepository.findById(member.getId()).orElseThrow();
    assertThat(after.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    assertThat(after.getEmail()).isEqualTo(before.getEmail());
    assertThat(after.getNickname()).isEqualTo(before.getNickname());
    assertThat(after.getPhone()).isEqualTo(before.getPhone());
    assertThat(after.getAccountNoEnc()).isEqualTo(before.getAccountNoEnc());
    assertThat(after.getTokenVersion()).isEqualTo(before.getTokenVersion());
    // 탈퇴 기록에는 member_id가 없어 이 회원의 이메일 · 휴대폰 HMAC으로 찾는다
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from member_withdrawal_record where email_hash = ? or phone_hash = ?", Long.class,
        memberHmac.emailHash(before.getEmail()), memberHmac.phoneHash(before.getPhone()))).isZero();
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from member_token_revocation where member_id = ?", Long.class, member.getId())).isZero();
  }

  // ===== 도우미 =====

  private record Login(String accessToken, String refreshToken) {
  }

  private Login login(Member member) throws Exception {
    HttpResponse<String> response = send("POST", "/api/v1/auth/login", null,
        "{\"email\":\"" + member.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}");
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
    return memberRepository.save(Member.createLocal("scenario" + n + "@test.com", passwordEncoder.encode(PASSWORD),
        "시나리오" + n, String.format("011%08d", n), "004", "v1:test-account", null, "홍길동"));
  }

  private static KeyPair newKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}

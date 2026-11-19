package com.justcommit.backend.member.unlink.presentation;

import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.common.testcontainers.SharedRedis;
import com.justcommit.backend.member.unlink.infrastructure.repository.KakaoUnlinkReceiptRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * [S-B10] 카카오 연결 해제 웹훅 수신 (#422). 실제 HTTP로 받아 수신 기록만 남는지 확인한다.
 * 키가 틀리면 401(기록 없음), 맞으면 값과 관계없이 200. 저장 실패 · 지연에도 200을 3초 안에 돌려준다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class KakaoUnlinkWebhookIntegrationTest {

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final String PATH = "/api/v1/webhooks/kakao/unlink";
  private static final String AUTHORIZATION = "KakaoAK test-kakao-admin-key";
  private static final String APP_ID = "1234567";

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    SharedPostgres.register(registry);
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
    SharedRedis.register(registry);
  }

  @Value("${local.server.port}")
  private int port;

  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired TransactionTemplate transactionTemplate;

  // 저장 실패를 만들 때만 동작을 바꾸고, 평소에는 실제 빈 그대로
  @MockitoSpyBean KakaoUnlinkReceiptRepository receiptRepository;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from member_kakao_unlink_receipt");
  }

  @Test
  @DisplayName("[S-B10] 어드민 키가 없거나 다르면 401, 수신 기록 없음")
  void unauthorized() throws Exception {
    assertThat(get(null, params(APP_ID, "9100000001", "UNLINK_FROM_APPS")).statusCode()).isEqualTo(401);
    assertThat(get("KakaoAK wrong-key", params(APP_ID, "9100000001", "UNLINK_FROM_APPS")).statusCode()).isEqualTo(401);
    assertThat(post("KakaoAK", params(APP_ID, "9100000001", "UNLINK_FROM_APPS")).statusCode()).isEqualTo(401);
    assertThat(count()).isZero();
  }

  @Test
  @DisplayName("[S-B10] 정상 요청은 GET 쿼리 · POST 폼 모두 200, 회원번호 · 사유와 함께 PENDING으로 기록")
  void receiveGetAndPost() throws Exception {
    assertThat(get(AUTHORIZATION, params(APP_ID, "9100000002", "UNLINK_FROM_APPS")).statusCode()).isEqualTo(200);
    assertThat(post(AUTHORIZATION, params(APP_ID, "9100000003", "ACCOUNT_DELETE")).statusCode()).isEqualTo(200);

    assertThat(row("9100000002")).containsEntry("status", "PENDING").containsEntry("referrer_type", "UNLINK_FROM_APPS");
    assertThat(row("9100000003")).containsEntry("status", "PENDING").containsEntry("referrer_type", "ACCOUNT_DELETE");
  }

  @Test
  @DisplayName("[S-B10] 앱 ID가 없거나 다르면 200, 회원 처리 없이 IGNORED(INVALID_APP_ID)")
  void invalidAppId() throws Exception {
    assertThat(get(AUTHORIZATION, params("7654321", "9100000004", "UNLINK_FROM_APPS")).statusCode()).isEqualTo(200);

    assertThat(row("9100000004")).containsEntry("status", "IGNORED").containsEntry("last_error", "INVALID_APP_ID");
  }

  @Test
  @DisplayName("[S-B10] 회원번호가 없거나 숫자가 아니면 200, 회원번호 없이 IGNORED(INVALID_USER_ID)")
  void invalidUserId() throws Exception {
    assertThat(get(AUTHORIZATION, params(APP_ID, "abc", "UNLINK_FROM_APPS")).statusCode()).isEqualTo(200);

    assertThat(jdbcTemplate.queryForList(
        "select provider_id, status, last_error from member_kakao_unlink_receipt")).singleElement()
        .satisfies(r -> {
          assertThat(r.get("provider_id")).isNull();
          assertThat(r).containsEntry("status", "IGNORED").containsEntry("last_error", "INVALID_USER_ID");
        });
  }

  @Test
  @DisplayName("[S-B10] 알 수 없거나 너무 긴 사유는 200, IGNORED(UNKNOWN_REFERRER_TYPE). 길이를 넘는 사유는 저장하지 않음")
  void unknownReferrerType() throws Exception {
    assertThat(get(AUTHORIZATION, params(APP_ID, "9100000005", "SOMETHING_NEW")).statusCode()).isEqualTo(200);
    assertThat(get(AUTHORIZATION, params(APP_ID, "9100000006", "X".repeat(41))).statusCode()).isEqualTo(200);

    assertThat(row("9100000005")).containsEntry("status", "IGNORED").containsEntry("referrer_type", "SOMETHING_NEW")
        .containsEntry("last_error", "UNKNOWN_REFERRER_TYPE");
    assertThat(row("9100000006")).containsEntry("status", "IGNORED").containsEntry("last_error", "UNKNOWN_REFERRER_TYPE");
    assertThat(row("9100000006").get("referrer_type")).isNull();
  }

  @Test
  @DisplayName("[S-B10] 수신 기록 저장이 실패해도 200 (카카오 문서: 오류에도 200)")
  void saveFailureStillOk() throws Exception {
    doThrow(new DataAccessResourceFailureException("DB 실패 (테스트)")).when(receiptRepository).save(any());

    assertThat(get(AUTHORIZATION, params(APP_ID, "9100000007", "UNLINK_FROM_APPS")).statusCode()).isEqualTo(200);
    assertThat(count()).isZero();
  }

  @Test
  @DisplayName("[S-B10] 다른 트랜잭션이 테이블을 잠가 저장이 지연돼도 잠금 대기 제한(1초) 뒤 3초 안에 200")
  void saveDelayStillOkWithinThreeSeconds() throws Exception {
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      // 수신 저장(INSERT)이 기다리도록 테이블 전체를 잡고 있는다
      Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
        jdbcTemplate.execute("LOCK TABLE member_kakao_unlink_receipt IN ACCESS EXCLUSIVE MODE");
        locked.countDown();
        try {
          release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }));
      assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

      long start = System.nanoTime();
      HttpResponse<String> response = get(AUTHORIZATION, params(APP_ID, "9100000008", "UNLINK_FROM_APPS"));
      Duration took = Duration.ofNanos(System.nanoTime() - start);

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(took).isLessThan(Duration.ofSeconds(3));
      release.countDown();
      holder.get(10, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
    // 잠금 대기 제한에 걸려 저장되지 않음 (유실 위험은 문서 「한계」)
    assertThat(count()).isZero();
  }

  private HttpResponse<String> get(String authorization, Map<String, String> params) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url() + "?" + form(params))).GET();
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String authorization, Map<String, String> params) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url()))
        .header("Authorization", authorization)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form(params)))
        .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String url() {
    return "http://localhost:" + port + PATH;
  }

  private static Map<String, String> params(String appId, String userId, String referrerType) {
    return Map.of("app_id", appId, "user_id", userId, "referrer_type", referrerType);
  }

  private static String form(Map<String, String> params) {
    return params.entrySet().stream()
        .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
  }

  private long count() {
    return jdbcTemplate.queryForObject("select count(*) from member_kakao_unlink_receipt", Long.class);
  }

  private Map<String, Object> row(String providerId) {
    return jdbcTemplate.queryForMap(
        "select status, referrer_type, last_error from member_kakao_unlink_receipt where provider_id = ?", providerId);
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

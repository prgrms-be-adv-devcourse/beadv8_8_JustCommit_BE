package com.justcommit.backend.member.member.application;

import com.justcommit.backend.member.auth.application.LoginCommand;
import com.justcommit.backend.member.auth.application.LoginResult;
import com.justcommit.backend.member.auth.application.LoginService;
import com.justcommit.backend.member.auth.domain.EmailPurpose;
import com.justcommit.backend.member.auth.infrastructure.mail.VerificationMailSender;
import com.justcommit.backend.member.auth.infrastructure.redis.PasswordFailureStore;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;

/**
 * 현재 비밀번호 실패 제한 (#412, #461 리뷰)을 실제 PostgreSQL · Redis · HTTP로 확인한다.
 * - 로그인은 서비스를 직접 호출하고, 비밀번호 변경 · 재설정은 HTTP로 보낸다 (비밀번호 변경 통합 테스트와 같은 방식)
 * - 메일만 가짜로 바꿔 재설정 인증번호를 가로챈다
 * - 동시 요청은 회원 잠금 대기(기본 3초)에 걸려 409 MEMBER_BUSY로 끝날 수 있어, 응답 개수를 고정하지 않고
 *   추가로 기록된 오답이 한 건뿐인지를 본다
 * - 준비 데이터는 요청 전에 커밋해야 해서 @Transactional을 붙이지 않는다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class PasswordChangeLimitIntegrationTest {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final String OLD_PASSWORD = "plant1234";
  private static final String NEW_PASSWORD = "green5678";
  private static final String OTHER_PASSWORD = "third9012";
  private static final String WRONG_PASSWORD = "wrong1234";
  private static final AtomicInteger SEQ = new AtomicInteger();
  private static final Pattern RESET_TOKEN = Pattern.compile("\"resetToken\":\"([^\"]+)\"");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @Value("${local.server.port}")
  private int port;

  @Autowired LoginService loginService;
  @Autowired PasswordFailureStore failureStore;
  @Autowired MemberRepository memberRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired StringRedisTemplate redisTemplate;
  @Autowired JdbcTemplate jdbcTemplate;

  // 실제로 메일을 보내지 않고, 보낼 인증번호를 가로챈다 (재설정 복구 시나리오)
  @MockitoBean
  VerificationMailSender mailSender;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @AfterEach
  void cleanUp() {
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
  @DisplayName("1. 순차: 1~5번째 오답은 400, 6번째는 맞는 비밀번호여도 429 + Retry-After, 비밀번호는 그대로")
  void lockedAfterFiveFailures() throws Exception {
    Member member = saveMember();
    String accessToken = login(member, OLD_PASSWORD).accessToken();

    for (int i = 1; i <= 5; i++) {
      assertError(patchPassword(accessToken, WRONG_PASSWORD, NEW_PASSWORD), 400, MemberErrorCode.INVALID_CURRENT_PASSWORD);
    }
    HttpResponse<String> sixth = patchPassword(accessToken, OLD_PASSWORD, NEW_PASSWORD);

    assertError(sixth, 429, MemberErrorCode.PASSWORD_CHANGE_LOCKED);
    long retryAfter = Long.parseLong(sixth.headers().firstValue("retry-after").orElseThrow());
    assertThat(retryAfter).isBetween(1L, Duration.ofDays(1).toSeconds());
    assertThat(passwordEncoder.matches(OLD_PASSWORD, passwordOf(member.getId()))).isTrue();
    assertThat(redisTemplate.opsForValue().get(failureKey(member))).isEqualTo("0:5");
  }

  @Test
  @DisplayName("2. 4번 틀린 상태에서 오답 두 개가 겹쳐도 추가 기록은 한 건 (400 한 건, 나머지는 429 또는 잠금 대기 409)")
  void concurrentWrongAttemptsRecordOnce() throws Exception {
    Member member = saveMember();
    String accessToken = login(member, OLD_PASSWORD).accessToken();
    for (int i = 0; i < 4; i++) {
      failureStore.recordFailure(member.getId(), 0, Duration.ofDays(1));
    }

    List<HttpResponse<String>> responses = runTogether(2, () -> patchPassword(accessToken, WRONG_PASSWORD, NEW_PASSWORD));

    assertThat(redisTemplate.opsForValue().get(failureKey(member))).isEqualTo("0:5");
    assertThat(responses).filteredOn(response -> response.statusCode() == 400)
        .singleElement()
        .satisfies(response -> assertThat(response.body()).contains(codeOf(MemberErrorCode.INVALID_CURRENT_PASSWORD)));
    assertThat(responses).filteredOn(response -> response.statusCode() != 400)
        .singleElement()
        .satisfies(response -> assertThat(response.body())
            .containsAnyOf(codeOf(MemberErrorCode.PASSWORD_CHANGE_LOCKED), codeOf(MemberErrorCode.MEMBER_BUSY)));
    assertThat(passwordEncoder.matches(OLD_PASSWORD, passwordOf(member.getId()))).isTrue();
  }

  @Test
  @DisplayName("3. 5번 틀려 막힘 → 이메일로 재설정 → 새 비밀번호로 로그인 → 새 Access로 비밀번호 변경 성공")
  void recoverByPasswordReset() throws Exception {
    Member member = saveMember();
    String oldAccessToken = login(member, OLD_PASSWORD).accessToken();
    for (int i = 0; i < 5; i++) {
      patchPassword(oldAccessToken, WRONG_PASSWORD, NEW_PASSWORD);
    }
    assertError(patchPassword(oldAccessToken, OLD_PASSWORD, NEW_PASSWORD), 429, MemberErrorCode.PASSWORD_CHANGE_LOCKED);

    String resetToken = resetTokenOf(verifyResetCode(member.getEmail(), sendResetCode(member.getEmail())));
    assertThat(post("/api/v1/auth/passwordResets",
        "{\"resetToken\":\"" + resetToken + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}").statusCode()).isEqualTo(200);

    assertThat(redisTemplate.hasKey(failureKey(member))).isFalse();
    String newAccessToken = login(member, NEW_PASSWORD).accessToken();
    HttpResponse<String> change = patchPassword(newAccessToken, NEW_PASSWORD, OTHER_PASSWORD);

    assertThat(change.statusCode()).isEqualTo(200);
    assertThat(passwordEncoder.matches(OTHER_PASSWORD, passwordOf(member.getId()))).isTrue();
  }

  @Test
  @DisplayName("4. 두 번 틀린 뒤 맞는 비밀번호로 변경하면 실패 횟수가 지워진다")
  void successClearsFailures() throws Exception {
    Member member = saveMember();
    String accessToken = login(member, OLD_PASSWORD).accessToken();
    patchPassword(accessToken, WRONG_PASSWORD, NEW_PASSWORD);
    patchPassword(accessToken, WRONG_PASSWORD, NEW_PASSWORD);
    assertThat(redisTemplate.opsForValue().get(failureKey(member))).isEqualTo("0:2");

    HttpResponse<String> change = patchPassword(accessToken, OLD_PASSWORD, NEW_PASSWORD);

    assertThat(change.statusCode()).isEqualTo(200);
    assertThat(redisTemplate.hasKey(failureKey(member))).isFalse();
  }

  // ===== 도우미 =====

  private HttpResponse<String> patchPassword(String accessToken, String current, String next) throws Exception {
    String body = "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}";
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/users/me/password"))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + accessToken)
        .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
        .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  // 재설정 인증번호를 요청하고, 메일로 보내려던 번호를 가로챈다
  private String sendResetCode(String email) throws Exception {
    HttpResponse<String> send = post("/api/v1/auth/email/send",
        "{\"email\":\"" + email + "\",\"purpose\":\"PASSWORD_RESET\"}");
    assertThat(send.statusCode()).isEqualTo(200);
    ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
    then(mailSender).should().send(eq(email), code.capture(), any(), eq(EmailPurpose.PASSWORD_RESET));
    return code.getValue();
  }

  private HttpResponse<String> verifyResetCode(String email, String code) throws Exception {
    return post("/api/v1/auth/email/verify",
        "{\"email\":\"" + email + "\",\"code\":\"" + code + "\",\"purpose\":\"PASSWORD_RESET\"}");
  }

  private static String resetTokenOf(HttpResponse<String> verifyResponse) {
    assertThat(verifyResponse.statusCode()).isEqualTo(200);
    Matcher matcher = RESET_TOKEN.matcher(verifyResponse.body());
    assertThat(matcher.find()).as("확인 응답에 resetToken이 있어야 함: %s", verifyResponse.body()).isTrue();
    return matcher.group(1);
  }

  private HttpResponse<String> post(String path, String json) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  // 같은 요청을 여러 스레드에서 같은 순간에 시작시키고 응답을 모은다
  private <T> List<T> runTogether(int threads, Callable<T> task) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<T>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(executor.submit(() -> {
          start.await();
          return task.call();
        }));
      }
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get(30, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      executor.shutdownNow();
    }
  }

  private static void assertError(HttpResponse<String> response, int status, MemberErrorCode errorCode) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.body()).contains(codeOf(errorCode));
  }

  private static String codeOf(MemberErrorCode errorCode) {
    return "\"code\":\"" + errorCode.name() + "\"";
  }

  private static String failureKey(Member member) {
    return "auth:password:failures:" + member.getId();
  }

  private LoginResult login(Member member, String password) {
    return loginService.login(new LoginCommand(member.getEmail(), password));
  }

  private String passwordOf(Long memberId) {
    return jdbcTemplate.queryForObject("select password from member where id = ?", String.class, memberId);
  }

  private Member saveMember() {
    int n = SEQ.incrementAndGet();
    return memberRepository.save(Member.createLocal("limit" + n + "@test.com", passwordEncoder.encode(OLD_PASSWORD),
        "제한" + n, String.format("015%08d", n), "004", "v1:test-account", "홍길동"));
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

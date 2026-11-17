package com.justcommit.backend.member.auth.presentation;

import com.justcommit.backend.member.auth.domain.EmailPurpose;
import com.justcommit.backend.member.auth.infrastructure.mail.AccountReminderMailSender;
import com.justcommit.backend.member.auth.infrastructure.mail.VerificationMailSender;
import com.justcommit.backend.member.config.MailExecutorConfig;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.timeout;

/**
 * 메일 비동기 발송 (#421)을 실제 메일 실행기 · PostgreSQL · Redis · HTTP로 확인한다.
 * - 다른 통합 테스트는 메일 실행기를 바로 실행하는 실행기로 바꿔 업무 흐름만 보고, 이 테스트는 yml 설정 그대로의 실행기를 쓴다
 * - 두 메일 클래스를 가짜로 바꿔 실제로 보내지 않는다. 가짜 SMTP를 래치로 막거나 실패시켜 응답 · 정리 시점을 확인한다
 * - 검증 범위: member 서비스(보안 필터 포함)까지, Gateway는 거치지 않는다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class MailAsyncSendIntegrationTest {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final AtomicInteger SEQ = new AtomicInteger();
  private static final Duration WAIT = Duration.ofSeconds(5);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @Value("${local.server.port}")
  private int port;

  @Autowired ApplicationContext context;
  @Autowired MemberRepository memberRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired StringRedisTemplate redisTemplate;
  @Autowired JdbcTemplate jdbcTemplate;

  @MockitoBean
  VerificationMailSender verificationMailSender;

  @MockitoBean
  AccountReminderMailSender reminderMailSender;

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
  @DisplayName("1. 가입 인증번호: SMTP가 끝나지 않아도 HTTP 200과 발송 요청 문구가 먼저 오고, 메일은 메일 실행기 스레드에서 보낸다")
  void signupCodeRespondsBeforeSmtp() throws Exception {
    String email = newEmail();
    CountDownLatch smtpStarted = new CountDownLatch(1);
    CountDownLatch smtpRelease = new CountDownLatch(1);
    CountDownLatch smtpDone = new CountDownLatch(1);
    AtomicReference<String> mailThread = new AtomicReference<>();
    willAnswer(invocation -> {
      mailThread.set(Thread.currentThread().getName());
      smtpStarted.countDown();
      smtpRelease.await(10, TimeUnit.SECONDS);
      smtpDone.countDown();
      return null;
    }).given(verificationMailSender).send(eq(email), anyString(), any(), eq(EmailPurpose.SIGNUP));

    try {
      HttpResponse<String> response = sendSignupCode(email);

      // 응답을 받은 시점에 SMTP는 시작됐지만 아직 끝나지 않음 (요청이 SMTP를 기다렸다면 smtpDone이 이미 내려가 있음)
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("인증번호 발송을 요청했습니다.");
      assertThat(smtpStarted.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
      assertThat(smtpDone.getCount()).isEqualTo(1);
      assertThat(mailThread.get()).startsWith("mail-");
    } finally {
      // 검증이 중간에 실패해도 막아 둔 메일 스레드를 푼다
      smtpRelease.countDown();
    }
    assertThat(smtpDone.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
  }

  @Test
  @DisplayName("2. 가입 계정 안내: 200으로 응답하고, 안내 메일도 메일 실행기 스레드에서 보낸다")
  void accountReminderUsesMailExecutor() throws Exception {
    Member local = saveMember();
    CountDownLatch smtpDone = new CountDownLatch(1);
    AtomicReference<String> mailThread = new AtomicReference<>();
    willAnswer(invocation -> {
      mailThread.set(Thread.currentThread().getName());
      smtpDone.countDown();
      return null;
    }).given(reminderMailSender).send(local.getEmail(), Provider.LOCAL);

    assertThat(remind(local.getEmail()).statusCode()).isEqualTo(200);

    assertThat(smtpDone.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    assertThat(mailThread.get()).startsWith("mail-");
  }

  @Test
  @DisplayName("3. 가입 인증번호: 응답 뒤 SMTP가 실패하면 메일 스레드가 인증번호 · 재발송 제한을 지우고, 같은 이메일로 바로 다시 요청할 수 있다")
  void smtpFailureReleasesAndAllowsRetry() throws Exception {
    String email = newEmail();
    willThrow(new MailSendException("smtp down")).willDoNothing()
        .given(verificationMailSender).send(eq(email), anyString(), any(), eq(EmailPurpose.SIGNUP));

    assertThat(sendSignupCode(email).statusCode()).isEqualTo(200);

    // 메일 스레드의 정리가 끝날 때까지 기다린 뒤 재요청 (끝나기 전에 보내면 재발송 제한에 걸림)
    await().atMost(WAIT).untilAsserted(() -> {
      assertThat(redisTemplate.hasKey(codeKey(email))).isFalse();
      assertThat(redisTemplate.hasKey(cooldownKey(email))).isFalse();
    });

    assertThat(sendSignupCode(email).statusCode()).isEqualTo(200);

    then(verificationMailSender).should(timeout(WAIT.toMillis()).times(2))
        .send(eq(email), anyString(), any(), eq(EmailPurpose.SIGNUP));
    // 첫 요청의 예약은 되돌려져 하루 횟수에는 두 번째 요청만 남는다
    assertThat(redisTemplate.opsForSet().size(dailyKey(email))).isEqualTo(1L);
  }

  @Test
  @DisplayName("4. 가입 인증번호: SMTP가 정상이면 인증번호 · 재발송 제한을 그대로 둔다")
  void smtpSuccessKeepsReservation() throws Exception {
    String email = newEmail();
    CountDownLatch smtpDone = new CountDownLatch(1);
    willAnswer(invocation -> {
      smtpDone.countDown();
      return null;
    }).given(verificationMailSender).send(eq(email), anyString(), any(), eq(EmailPurpose.SIGNUP));

    assertThat(sendSignupCode(email).statusCode()).isEqualTo(200);

    // 가짜 SMTP가 끝난 뒤 확인. 메일 작업의 성공 경로에는 발송 다음 코드가 없어(정리는 MailException일 때만)
    // 이 시점 이후 Redis가 바뀌지 않는다 (EmailVerificationService.sendCodeMail)
    assertThat(smtpDone.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    assertThat(redisTemplate.hasKey(codeKey(email))).isTrue();
    assertThat(redisTemplate.hasKey(cooldownKey(email))).isTrue();
    assertThat(redisTemplate.opsForSet().size(dailyKey(email))).isEqualTo(1L);
  }

  @Test
  @DisplayName("5. 메일 실행기는 이름으로만 주입되는 별도 빈이고, Spring Boot 기본 실행기(applicationTaskExecutor)도 유지된다")
  void defaultExecutorIsKept() {
    Object mailExecutor = context.getBean(MailExecutorConfig.MAIL_TASK_EXECUTOR);
    Object applicationExecutor = context.getBean("applicationTaskExecutor");

    assertThat(mailExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
    assertThat(applicationExecutor).isNotSameAs(mailExecutor);
  }

  // ===== 도우미 =====

  private HttpResponse<String> sendSignupCode(String email) throws Exception {
    return post("/api/v1/auth/email/send", "{\"email\":\"" + email + "\"}");
  }

  private HttpResponse<String> remind(String email) throws Exception {
    return post("/api/v1/auth/accountReminders", "{\"email\":\"" + email + "\"}");
  }

  private HttpResponse<String> post(String path, String json) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  // Redis 키 (docs/reference/redis-keys.md)
  private static String codeKey(String email) {
    return "auth:email:signup:" + email;
  }

  private static String cooldownKey(String email) {
    return "auth:email:cooldown:" + email;
  }

  private static String dailyKey(String email) {
    return "auth:email:daily:signup:" + email;
  }

  private static String newEmail() {
    return "async" + SEQ.incrementAndGet() + "@test.com";
  }

  private Member saveMember() {
    int n = SEQ.incrementAndGet();
    return memberRepository.save(Member.createLocal("async" + n + "@test.com", passwordEncoder.encode("plant1234"),
        "비동기" + n, String.format("015%08d", n), "004", "v1:test-account", null, "홍길동"));
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

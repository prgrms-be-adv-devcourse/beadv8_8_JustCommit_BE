package com.justcommit.backend.member.auth.presentation;

import com.justcommit.backend.member.auth.infrastructure.mail.AccountReminderMailSender;
import com.justcommit.backend.member.auth.infrastructure.mail.VerificationMailSender;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
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
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * 가입 계정 안내 (#412)를 실제 PostgreSQL · Redis · HTTP로 확인한다.
 * - 유효한 요청은 가입 여부 · 로그인 방식 · 발송 제한 · SMTP 실패와 관계없이 같은 200 (입력 오류는 400)
 * - 같은 이메일의 발송 예약 제한(60초 · 하루 5회)은 인증 메일과 공유한다. 공유 여부는 제한을 응답으로 드러내는
 *   가입 인증번호 발송으로 확인한다 (가입된 이메일은 예약 전에 409가 나므로 미가입 이메일을 쓴다)
 * - IP 기준 제한은 Gateway에서 API별로 따로 센다 (Gateway 테스트에서 확인)
 * - 두 메일 클래스를 가짜로 바꿔 실제로 보내지 않는다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class AccountReminderIntegrationTest {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final AtomicInteger SEQ = new AtomicInteger();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @Value("${local.server.port}")
  private int port;

  @Autowired MemberRepository memberRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired StringRedisTemplate redisTemplate;
  @Autowired JdbcTemplate jdbcTemplate;

  @MockitoBean
  AccountReminderMailSender reminderMailSender;

  @MockitoBean
  VerificationMailSender verificationMailSender;

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
  @DisplayName("1. 로컬 · 카카오 · 미가입 · 탈퇴 · 네이버 · 재발송 제한 중 · SMTP 실패 모두 같은 200과 본문, 메일은 지원하는 방식의 활동 회원에게만")
  void sameResponseForEveryCase() throws Exception {
    Member local = saveMember();
    Member kakao = saveSocial("KAKAO");
    Member naver = saveSocial("NAVER");
    Member deleted = saveMember();
    jdbcTemplate.update("update member set status = 'DELETED' where id = ?", deleted.getId());
    Member smtpFail = saveMember();
    willThrow(new MailSendException("smtp down")).given(reminderMailSender).send(eq(smtpFail.getEmail()), any());
    String unknownEmail = "nobody" + SEQ.incrementAndGet() + "@test.com";

    List<HttpResponse<String>> responses = List.of(
        remind(local.getEmail()),
        remind(kakao.getEmail()),
        remind(unknownEmail),
        remind(deleted.getEmail()),
        remind(naver.getEmail()),
        remind(local.getEmail()), // 재발송 제한 중
        remind(smtpFail.getEmail()));

    HttpResponse<String> first = responses.get(0);
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(first.body()).contains("가입된 계정이면 안내 메일을 보내드립니다.");
    assertThat(responses).allSatisfy(response -> {
      assertThat(response.statusCode()).isEqualTo(first.statusCode());
      assertThat(response.body()).isEqualTo(first.body());
    });

    then(reminderMailSender).should(times(1)).send(local.getEmail(), Provider.LOCAL);
    then(reminderMailSender).should().send(kakao.getEmail(), Provider.KAKAO);
    then(reminderMailSender).should(never()).send(eq(unknownEmail), any());
    then(reminderMailSender).should(never()).send(eq(deleted.getEmail()), any());
    then(reminderMailSender).should(never()).send(eq(naver.getEmail()), any());
  }

  @Test
  @DisplayName("2. 이메일 형식이 틀리면 400")
  void invalidEmail() throws Exception {
    assertThat(remind("not-an-email").statusCode()).isEqualTo(400);
  }

  @Test
  @DisplayName("3. 안내 직후 같은 이메일로 가입 인증번호를 요청하면 재발송 제한을 공유해 429 EMAIL_SEND_COOLDOWN")
  void cooldownIsShared() throws Exception {
    String email = "shared" + SEQ.incrementAndGet() + "@test.com";

    assertThat(remind(email).statusCode()).isEqualTo(200);
    HttpResponse<String> signupSend = sendSignupCode(email);

    assertThat(signupSend.statusCode()).isEqualTo(429);
    assertThat(signupSend.body()).contains("\"code\":\"EMAIL_SEND_COOLDOWN\"");
  }

  @Test
  @DisplayName("4. 안내와 가입 인증번호 발송을 섞어 5건이면 하루 한도를 공유해 다음 가입 발송은 429 EMAIL_SEND_DAILY_LIMIT")
  void dailyLimitIsShared() throws Exception {
    String email = "daily" + SEQ.incrementAndGet() + "@test.com";

    assertThat(remind(email).statusCode()).isEqualTo(200);
    clearCooldown(email);
    assertThat(sendSignupCode(email).statusCode()).isEqualTo(200);
    clearCooldown(email);
    assertThat(remind(email).statusCode()).isEqualTo(200);
    clearCooldown(email);
    assertThat(sendSignupCode(email).statusCode()).isEqualTo(200);
    clearCooldown(email);
    assertThat(remind(email).statusCode()).isEqualTo(200);
    clearCooldown(email);

    HttpResponse<String> sixth = sendSignupCode(email);

    assertThat(redisTemplate.opsForSet().size("auth:email:daily:" + email)).isEqualTo(5L);
    assertThat(sixth.statusCode()).isEqualTo(429);
    assertThat(sixth.body()).contains("\"code\":\"EMAIL_SEND_DAILY_LIMIT\"");
  }

  @Test
  @DisplayName("5. 안내가 SMTP 실패면 자기 예약이 복구되어, 바로 다시 요청해도 재발송 제한 없이 발송을 다시 시도한다")
  void smtpFailureReleasesReservation() throws Exception {
    Member local = saveMember();
    willThrow(new MailSendException("smtp down")).willDoNothing()
        .given(reminderMailSender).send(local.getEmail(), Provider.LOCAL);

    assertThat(remind(local.getEmail()).statusCode()).isEqualTo(200);
    assertThat(remind(local.getEmail()).statusCode()).isEqualTo(200);

    then(reminderMailSender).should(times(2)).send(local.getEmail(), Provider.LOCAL);
    assertThat(redisTemplate.opsForSet().size("auth:email:daily:" + local.getEmail())).isEqualTo(1L);
  }

  // ===== 도우미 =====

  private HttpResponse<String> remind(String email) throws Exception {
    return post("/api/v1/auth/accountReminders", "{\"email\":\"" + email + "\"}");
  }

  private HttpResponse<String> sendSignupCode(String email) throws Exception {
    return post("/api/v1/auth/email/send", "{\"email\":\"" + email + "\"}");
  }

  // 60초를 기다리는 대신 재발송 제한 키만 지운다 (하루 횟수는 그대로)
  private void clearCooldown(String email) {
    redisTemplate.delete("auth:email:cooldown:" + email);
  }

  private HttpResponse<String> post(String path, String json) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private Member saveMember() {
    int n = SEQ.incrementAndGet();
    return memberRepository.save(Member.createLocal("remind" + n + "@test.com", passwordEncoder.encode("plant1234"),
        "안내" + n, String.format("014%08d", n), "004", "v1:test-account", "홍길동"));
  }

  // 소셜 회원: 로컬로 저장한 뒤 비밀번호를 비우고 로그인 방식을 바꾼다 (비밀번호 변경 통합 테스트와 같은 방식)
  private Member saveSocial(String provider) {
    Member member = saveMember();
    jdbcTemplate.update("update member set password = null, provider = ?, provider_id = ? where id = ?",
        provider, provider.toLowerCase() + "-" + member.getId(), member.getId());
    return member;
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

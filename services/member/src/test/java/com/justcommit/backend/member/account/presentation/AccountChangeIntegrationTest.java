package com.justcommit.backend.member.account.presentation;

import com.justcommit.backend.common.security.TokenHash;
import com.justcommit.backend.member.auth.domain.EmailPurpose;
import com.justcommit.backend.member.auth.infrastructure.mail.VerificationMailSender;
import com.justcommit.backend.member.domain.Member;
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
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;

/**
 * 계좌 변경 인증번호 발송 · 변경 토큰 발급 (#413)을 실제 PostgreSQL · Redis · HTTP로 확인한다.
 * - 두 경로 모두 로그인 필수, 이메일은 요청이 아니라 회원 정보의 값을 쓴다
 * - 메일 발송 클래스는 가짜로 바꿔, 보내려던 인증번호를 꺼내 확인 요청에 쓴다
 * - 공개 인증 경로(/auth/email/send · verify)는 계좌 변경 목적을 계속 거절한다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class AccountChangeIntegrationTest {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final AtomicInteger SEQ = new AtomicInteger();
  private static final String PASSWORD = "plant1234";
  private static final String VERIFICATIONS = "/api/v1/users/me/account/verifications";
  private static final String CHANGE_TOKENS = "/api/v1/users/me/account/changeTokens";
  private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");
  private static final Pattern CHANGE_TOKEN = Pattern.compile("\"accountChangeToken\":\"([^\"]+)\"");

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
  @DisplayName("1. 로그인 없이 호출하면 인증번호 발송 · 변경 토큰 발급 모두 401")
  void requiresLogin() throws Exception {
    assertThat(post(VERIFICATIONS, null, "").statusCode()).isEqualTo(401);
    assertThat(post(CHANGE_TOKENS, null, "{\"code\":\"123456\"}").statusCode()).isEqualTo(401);
  }

  @Test
  @DisplayName("2. 발송 → 회원 이메일로 받은 번호로 확인하면 10분짜리 변경 토큰, Redis에는 토큰 해시 키로 회원 id · 인증 버전")
  void sendAndIssueToken() throws Exception {
    Member member = saveMember();
    String accessToken = accessToken(member.getEmail());

    HttpResponse<String> sent = post(VERIFICATIONS, accessToken, "");
    assertThat(sent.statusCode()).as(sent.body()).isEqualTo(200);
    assertThat(sent.body()).contains("\"expiresIn\":300", "\"resendAvailableIn\":60");

    HttpResponse<String> issued = post(CHANGE_TOKENS, accessToken, codeBody(sentCode(member.getEmail())));
    assertThat(issued.statusCode()).as(issued.body()).isEqualTo(200);
    assertThat(issued.body()).contains("\"expiresIn\":600");
    Matcher matcher = CHANGE_TOKEN.matcher(issued.body());
    assertThat(matcher.find()).isTrue();
    String changeToken = matcher.group(1);

    assertThat(redisTemplate.opsForValue().get("auth:account-change:" + TokenHash.sha256Hex(changeToken)))
        .isEqualTo(member.getId() + ":" + member.getTokenVersion());
  }

  @Test
  @DisplayName("3. 틀린 번호면 400 EMAIL_CODE_MISMATCH, 변경 토큰 없음")
  void wrongCode() throws Exception {
    Member member = saveMember();
    String accessToken = accessToken(member.getEmail());
    post(VERIFICATIONS, accessToken, "");
    String wrongCode = sentCode(member.getEmail()).equals("000000") ? "111111" : "000000";

    HttpResponse<String> response = post(CHANGE_TOKENS, accessToken, codeBody(wrongCode));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("\"code\":\"EMAIL_CODE_MISMATCH\"");
    assertThat(redisTemplate.keys("auth:account-change:*")).isEmpty();
  }

  @Test
  @DisplayName("4. 발송 직후 다시 요청하면 429 EMAIL_SEND_COOLDOWN + Retry-After")
  void cooldown() throws Exception {
    Member member = saveMember();
    String accessToken = accessToken(member.getEmail());
    post(VERIFICATIONS, accessToken, "");

    HttpResponse<String> again = post(VERIFICATIONS, accessToken, "");

    assertThat(again.statusCode()).isEqualTo(429);
    assertThat(again.body()).contains("\"code\":\"EMAIL_SEND_COOLDOWN\"");
    assertThat(again.headers().firstValue("Retry-After")).isPresent();
  }

  @Test
  @DisplayName("5. 공개 인증 경로는 계좌 변경 목적의 발송 · 확인을 계속 거절 (400 EMAIL_PURPOSE_NOT_SUPPORTED)")
  void publicPathsRejectAccountChange() throws Exception {
    HttpResponse<String> send = post("/api/v1/auth/email/send", null,
        "{\"email\":\"public@test.com\",\"purpose\":\"ACCOUNT_CHANGE\"}");
    HttpResponse<String> verify = post("/api/v1/auth/email/verify", null,
        "{\"email\":\"public@test.com\",\"code\":\"123456\",\"purpose\":\"ACCOUNT_CHANGE\"}");

    assertThat(send.statusCode()).isEqualTo(400);
    assertThat(send.body()).contains("\"code\":\"EMAIL_PURPOSE_NOT_SUPPORTED\"");
    assertThat(verify.statusCode()).isEqualTo(400);
    assertThat(verify.body()).contains("\"code\":\"EMAIL_PURPOSE_NOT_SUPPORTED\"");
  }

  // ===== 도우미 =====

  // 가짜 메일 발송 클래스에 전달된 계좌 변경 인증번호 (요청한 회원의 이메일로 보냈는지도 함께 확인)
  private String sentCode(String email) {
    ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
    then(verificationMailSender).should().send(eq(email), code.capture(), any(), eq(EmailPurpose.ACCOUNT_CHANGE));
    return code.getValue();
  }

  private static String codeBody(String code) {
    return "{\"code\":\"" + code + "\"}";
  }

  private String accessToken(String email) throws Exception {
    HttpResponse<String> response = post("/api/v1/auth/login", null,
        "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
    assertThat(response.statusCode()).isEqualTo(200);
    Matcher matcher = ACCESS_TOKEN.matcher(response.body());
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }

  private HttpResponse<String> post(String path, String accessToken, String json) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json));
    if (accessToken != null) {
      request.header("Authorization", "Bearer " + accessToken);
    }
    return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private Member saveMember() {
    int n = SEQ.incrementAndGet();
    return memberRepository.save(Member.createLocal("account" + n + "@test.com", passwordEncoder.encode(PASSWORD),
        "계좌" + n, String.format("019%08d", n), "004", "v1:test-account", null, "홍길동"));
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

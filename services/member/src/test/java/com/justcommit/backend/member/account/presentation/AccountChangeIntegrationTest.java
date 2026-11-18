package com.justcommit.backend.member.account.presentation;

import com.justcommit.backend.common.security.TokenHash;
import com.justcommit.backend.member.auth.domain.EmailPurpose;
import com.justcommit.backend.member.auth.infrastructure.mail.VerificationMailSender;
import com.justcommit.backend.member.config.MailExecutorConfig;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.infrastructure.crypto.AccountCipher;
import com.justcommit.backend.member.infrastructure.crypto.MemberHmac;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.common.testcontainers.SharedRedis;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Timestamp;
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
 * - 계좌 변경(PATCH): 토큰이 남는 실패(형식 · 중복)와 소모되는 경우(성공 · 같은 계좌)를 나눠 확인한다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class AccountChangeIntegrationTest {

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final AtomicInteger SEQ = new AtomicInteger();
  private static final String PASSWORD = "plant1234";
  private static final String VERIFICATIONS = "/api/v1/users/me/account/verifications";
  private static final String CHANGE_TOKENS = "/api/v1/users/me/account/changeTokens";
  private static final String ACCOUNT = "/api/v1/users/me/account";
  private static final String NEW_BANK = "088";
  private static final String NEW_ACCOUNT_NO = "98765432101";
  private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");
  private static final Pattern CHANGE_TOKEN = Pattern.compile("\"accountChangeToken\":\"([^\"]+)\"");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    SharedPostgres.register(registry);
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
    SharedRedis.register(registry);
  }

  @Value("${local.server.port}")
  private int port;

  @Autowired MemberRepository memberRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired StringRedisTemplate redisTemplate;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired MemberHmac memberHmac;
  @Autowired AccountCipher accountCipher;

  @MockitoBean
  VerificationMailSender verificationMailSender;

  // 업무 흐름 검증이 목적이라 메일 작업을 요청 스레드에서 바로 실행한다 (응답 직후 메일 발송자 호출을 바로 검증)
  // 비동기 동작은 별도 통합 테스트에서 실제 실행기로 검증 (#421)
  @TestBean(name = MailExecutorConfig.MAIL_TASK_EXECUTOR)
  TaskExecutor mailTaskExecutor;

  static TaskExecutor mailTaskExecutor() {
    return new SyncTaskExecutor();
  }

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
    assertThat(patch(null, changeBody("token", NEW_BANK, NEW_ACCOUNT_NO, "김철수")).statusCode()).isEqualTo(401);
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

  // ===== 계좌 변경 (PATCH) =====

  @Test
  @DisplayName("6. 변경 성공: 은행 · HMAC · 암호문 · 예금주가 바뀌고 토큰은 소모, 응답 · 로그에 계좌번호 · 토큰 원문 없음")
  void changeAccount(CapturedOutput output) throws Exception {
    Member member = saveMember();
    String accessToken = accessToken(member.getEmail());
    String changeToken = issueChangeToken(member, accessToken);

    HttpResponse<String> response = patch(accessToken, changeBody(changeToken, NEW_BANK, NEW_ACCOUNT_NO, "김철수"));

    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(response.body()).contains("계좌가 변경되었습니다.").doesNotContain(NEW_ACCOUNT_NO);
    Member changed = memberRepository.findById(member.getId()).orElseThrow();
    assertThat(changed.getBankCode()).isEqualTo(NEW_BANK);
    assertThat(changed.getAccountHash()).isEqualTo(memberHmac.accountHash(NEW_BANK, NEW_ACCOUNT_NO));
    assertThat(accountCipher.decrypt(changed.getAccountNoEnc())).isEqualTo(NEW_ACCOUNT_NO);
    assertThat(changed.getAccountHolder()).isEqualTo("김철수");
    assertThat(redisTemplate.hasKey("auth:account-change:" + TokenHash.sha256Hex(changeToken))).isFalse();
    assertThat(output.getAll()).doesNotContain(NEW_ACCOUNT_NO).doesNotContain(changeToken);
  }

  @Test
  @DisplayName("7. 같은 토큰으로 다시 바꾸려 하면 400 INVALID_ACCOUNT_CHANGE_TOKEN (한 번만 사용)")
  void changeAccount_tokenOnce() throws Exception {
    Member member = saveMember();
    String accessToken = accessToken(member.getEmail());
    String changeToken = issueChangeToken(member, accessToken);
    assertThat(patch(accessToken, changeBody(changeToken, NEW_BANK, NEW_ACCOUNT_NO, "김철수")).statusCode()).isEqualTo(200);

    HttpResponse<String> again = patch(accessToken, changeBody(changeToken, "004", "11122233344", "김철수"));

    assertThat(again.statusCode()).isEqualTo(400);
    assertThat(again.body()).contains("\"code\":\"INVALID_ACCOUNT_CHANGE_TOKEN\"");
  }

  @Test
  @DisplayName("8. 형식 오류(400) · 다른 회원이 쓰는 계좌(409 DUPLICATE_ACCOUNT)로 거절돼도 토큰이 남아, 같은 토큰으로 다른 계좌로 바꿀 수 있다")
  void changeAccount_failuresKeepToken() throws Exception {
    memberRepository.save(Member.createLocal("taken@test.com", passwordEncoder.encode(PASSWORD), "선점",
        "01900000000", NEW_BANK, accountCipher.encrypt(NEW_ACCOUNT_NO), memberHmac.accountHash(NEW_BANK, NEW_ACCOUNT_NO),
        "이영희"));
    Member member = saveMember();
    String accessToken = accessToken(member.getEmail());
    String changeToken = issueChangeToken(member, accessToken);

    HttpResponse<String> invalid = patch(accessToken, changeBody(changeToken, NEW_BANK, "123", "김철수"));
    HttpResponse<String> duplicate = patch(accessToken, changeBody(changeToken, NEW_BANK, NEW_ACCOUNT_NO, "김철수"));
    HttpResponse<String> other = patch(accessToken, changeBody(changeToken, "004", "11122233344", "김철수"));

    assertThat(invalid.statusCode()).isEqualTo(400);
    assertThat(duplicate.statusCode()).isEqualTo(409);
    assertThat(duplicate.body()).contains("\"code\":\"DUPLICATE_ACCOUNT\"");
    assertThat(other.statusCode()).as(other.body()).isEqualTo(200);
    assertThat(memberRepository.findById(member.getId()).orElseThrow().getAccountHash())
        .isEqualTo(memberHmac.accountHash("004", "11122233344"));
  }

  @Test
  @DisplayName("9. 토큰을 받은 뒤 인증 버전이 바뀌었으면(비밀번호 변경 등) 400 INVALID_ACCOUNT_CHANGE_TOKEN, 계좌 그대로")
  void changeAccount_tokenVersionChanged() throws Exception {
    Member member = saveMember();
    String accessToken = accessToken(member.getEmail());
    String changeToken = issueChangeToken(member, accessToken);
    jdbcTemplate.update("update member set token_version = token_version + 1 where id = ?", member.getId());

    HttpResponse<String> response = patch(accessToken, changeBody(changeToken, NEW_BANK, NEW_ACCOUNT_NO, "김철수"));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("\"code\":\"INVALID_ACCOUNT_CHANGE_TOKEN\"");
    assertThat(memberRepository.findById(member.getId()).orElseThrow().getBankCode()).isEqualTo("004");
  }

  @Test
  @DisplayName("10. 은행 · 계좌번호 · 예금주가 모두 같으면 200이지만 저장하지 않아 계좌 변경 시각이 그대로, 토큰은 소모")
  void changeAccount_sameAccount() throws Exception {
    Member member = memberRepository.save(Member.createLocal("same@test.com", passwordEncoder.encode(PASSWORD), "같은계좌",
        "01900000001", NEW_BANK, accountCipher.encrypt(NEW_ACCOUNT_NO), memberHmac.accountHash(NEW_BANK, NEW_ACCOUNT_NO),
        "김철수"));
    Timestamp before = accountUpdatedAt(member.getId());
    String accessToken = accessToken(member.getEmail());
    String changeToken = issueChangeToken(member, accessToken);

    HttpResponse<String> response = patch(accessToken, changeBody(changeToken, NEW_BANK, NEW_ACCOUNT_NO, "김철수"));

    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(accountUpdatedAt(member.getId())).isEqualTo(before);
    assertThat(redisTemplate.hasKey("auth:account-change:" + TokenHash.sha256Hex(changeToken))).isFalse();
  }

  // ===== 도우미 =====

  // 발송 → 메일로 받은 번호 확인 → 계좌 변경 토큰
  private String issueChangeToken(Member member, String accessToken) throws Exception {
    post(VERIFICATIONS, accessToken, "");
    HttpResponse<String> issued = post(CHANGE_TOKENS, accessToken, codeBody(sentCode(member.getEmail())));
    assertThat(issued.statusCode()).as(issued.body()).isEqualTo(200);
    Matcher matcher = CHANGE_TOKEN.matcher(issued.body());
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }

  private static String changeBody(String token, String bankCode, String accountNo, String holder) {
    return "{\"accountChangeToken\":\"" + token + "\",\"bankCode\":\"" + bankCode
        + "\",\"accountNo\":\"" + accountNo + "\",\"accountHolder\":\"" + holder + "\"}";
  }

  private Timestamp accountUpdatedAt(Long memberId) {
    return jdbcTemplate.queryForObject("select account_updated_at from member where id = ?", Timestamp.class, memberId);
  }

  private HttpResponse<String> patch(String accessToken, String json) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + ACCOUNT))
        .header("Content-Type", "application/json")
        .method("PATCH", HttpRequest.BodyPublishers.ofString(json));
    if (accessToken != null) {
      request.header("Authorization", "Bearer " + accessToken);
    }
    return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

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

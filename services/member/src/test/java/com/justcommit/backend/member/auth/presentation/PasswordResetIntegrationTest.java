package com.justcommit.backend.member.auth.presentation;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.application.LoginCommand;
import com.justcommit.backend.member.auth.application.LoginResult;
import com.justcommit.backend.member.auth.application.LoginService;
import com.justcommit.backend.member.auth.domain.EmailPurpose;
import com.justcommit.backend.member.auth.infrastructure.mail.VerificationMailSender;
import com.justcommit.backend.member.config.MailExecutorConfig;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.member.application.PasswordChangeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * 비밀번호 재설정 (#412)을 실제 PostgreSQL · Redis · HTTP로 처음부터 끝까지 확인한다.
 * - 메일만 가짜로 바꿔, 실제로 보내지 않고 보낼 인증번호를 가로챈다
 * - 재설정 전 로그인 · 중간의 비밀번호 변경은 서비스를 직접 호출한다 (비밀번호 변경 통합 테스트와 같은 방식)
 * - 준비 데이터는 요청 전에 커밋해야 해서 @Transactional을 붙이지 않는다
 * - 검증 범위는 member 서비스의 보안 필터까지이며, Gateway를 거치는 경우는 아니다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class PasswordResetIntegrationTest {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final String OLD_PASSWORD = "plant1234";
  private static final String NEW_PASSWORD = "green5678";
  private static final String OTHER_PASSWORD = "third9012";
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
  @Autowired PasswordChangeService passwordChangeService;
  @Autowired MemberRepository memberRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired StringRedisTemplate redisTemplate;
  @Autowired JdbcTemplate jdbcTemplate;

  // 실제로 메일을 보내지 않고, 보낼 인증번호를 가로챈다
  @MockitoBean
  VerificationMailSender mailSender;

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
  @DisplayName("1. 정상 흐름: 재설정 후 200 · revocationCompleted true · Set-Cookie 없음, 재설정 전 Refresh로 재발급 401, 새 비밀번호로만 로그인")
  void resetFlow() throws Exception {
    Member member = saveMember();
    LoginResult before = login(member, OLD_PASSWORD);

    String token = resetTokenOf(verify(member.getEmail(), sendResetCode(member.getEmail()), EmailPurpose.PASSWORD_RESET));
    HttpResponse<String> response = resetPassword(token, NEW_PASSWORD);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"revocationCompleted\":true");
    assertThat(response.headers().allValues("set-cookie")).isEmpty();

    HttpResponse<String> reissue = reissueWithCookie(before.refreshToken());
    assertThat(reissue.statusCode()).isEqualTo(401);

    assertThatThrownBy(() -> login(member, OLD_PASSWORD))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.LOGIN_FAILED);
    assertThat(login(member, NEW_PASSWORD).accessToken()).isNotBlank();
  }

  @Test
  @DisplayName("2. 인증번호를 받은 뒤 비밀번호를 변경하면, 남은 그 번호로는 재설정 토큰을 받을 수 없다 (EMAIL_CODE_INVALID)")
  void codeIssuedBeforePasswordChange() throws Exception {
    Member member = saveMember();
    String code = sendResetCode(member.getEmail());

    passwordChangeService.changePassword(member.getId(), OLD_PASSWORD, NEW_PASSWORD);
    HttpResponse<String> response = verify(member.getEmail(), code, EmailPurpose.PASSWORD_RESET);

    assertError(response, 400, MemberErrorCode.EMAIL_CODE_INVALID);
    assertThat(redisTemplate.keys("auth:password-reset:*")).isEmpty();
  }

  @Test
  @DisplayName("3. 가입 목적으로 받은 인증번호로는 재설정 확인을 할 수 없고(EMAIL_CODE_INVALID), 그 번호로 가입 인증은 그대로 성공한다")
  void signupCodeCannotReset() throws Exception {
    String email = "signup" + SEQ.incrementAndGet() + "@test.com";
    HttpResponse<String> send = post("/api/v1/auth/email/send", "{\"email\":\"" + email + "\"}");
    assertThat(send.statusCode()).isEqualTo(200);
    ArgumentCaptor<String> signupCode = ArgumentCaptor.forClass(String.class);
    then(mailSender).should().send(eq(email), signupCode.capture(), any(), eq(EmailPurpose.SIGNUP));

    HttpResponse<String> response = verify(email, signupCode.getValue(), EmailPurpose.PASSWORD_RESET);

    assertError(response, 400, MemberErrorCode.EMAIL_CODE_INVALID);

    // 재설정 확인이 가입 번호를 읽거나 지우지 않았는지: 같은 번호로 가입 인증은 성공하고, 응답은 기존 가입 응답 그대로(data 없음)
    HttpResponse<String> signupVerify = verify(email, signupCode.getValue(), EmailPurpose.SIGNUP);
    assertThat(signupVerify.statusCode()).isEqualTo(200);
    assertThat(signupVerify.body()).contains("이메일 인증이 완료되었습니다.").doesNotContain("\"data\"");
    assertThat(redisTemplate.hasKey("auth:email:verified:signup:" + email)).isTrue();
  }

  @Test
  @DisplayName("4. 같은 재설정 토큰은 한 번만 쓸 수 있다 (두 번째 INVALID_RESET_TOKEN)")
  void tokenCannotBeReused() throws Exception {
    Member member = saveMember();
    String token = resetTokenOf(verify(member.getEmail(), sendResetCode(member.getEmail()), EmailPurpose.PASSWORD_RESET));

    assertThat(resetPassword(token, NEW_PASSWORD).statusCode()).isEqualTo(200);
    HttpResponse<String> second = resetPassword(token, OTHER_PASSWORD);

    assertError(second, 400, MemberErrorCode.INVALID_RESET_TOKEN);
    assertThat(passwordEncoder.matches(NEW_PASSWORD, passwordOf(member.getId()))).isTrue();
  }

  @Test
  @DisplayName("5. 토큰을 받은 뒤 비밀번호가 바뀌면 그 토큰으로는 재설정할 수 없다 (INVALID_RESET_TOKEN, 비밀번호 그대로)")
  void tokenIssuedBeforePasswordChange() throws Exception {
    Member member = saveMember();
    String token = resetTokenOf(verify(member.getEmail(), sendResetCode(member.getEmail()), EmailPurpose.PASSWORD_RESET));

    passwordChangeService.changePassword(member.getId(), OLD_PASSWORD, NEW_PASSWORD);
    HttpResponse<String> response = resetPassword(token, OTHER_PASSWORD);

    assertError(response, 400, MemberErrorCode.INVALID_RESET_TOKEN);
    assertThat(passwordEncoder.matches(NEW_PASSWORD, passwordOf(member.getId()))).isTrue();
  }

  @Test
  @DisplayName("6. 가입 안 된 이메일 · 소셜 회원의 재설정 발송은 정상 로컬 계정과 상태 코드 · 본문이 같고, 메일은 로컬 계정에만 간다")
  void sameResponseForEveryAccount() throws Exception {
    Member local = saveMember();
    Member social = saveMember();
    jdbcTemplate.update("update member set password = null, provider = 'KAKAO', provider_id = ? where id = ?",
        "kakao-" + social.getId(), social.getId());
    String unknownEmail = "nobody" + SEQ.incrementAndGet() + "@test.com";

    HttpResponse<String> localResponse = sendReset(local.getEmail());
    HttpResponse<String> socialResponse = sendReset(social.getEmail());
    HttpResponse<String> unknownResponse = sendReset(unknownEmail);

    assertThat(localResponse.statusCode()).isEqualTo(200);
    assertThat(localResponse.body()).contains("재설정 가능한 계정이면 인증번호를 보내드립니다.");
    assertThat(socialResponse.statusCode()).isEqualTo(localResponse.statusCode());
    assertThat(socialResponse.body()).isEqualTo(localResponse.body());
    assertThat(unknownResponse.statusCode()).isEqualTo(localResponse.statusCode());
    assertThat(unknownResponse.body()).isEqualTo(localResponse.body());

    then(mailSender).should().send(eq(local.getEmail()), anyString(), any(), eq(EmailPurpose.PASSWORD_RESET));
    then(mailSender).should(never()).send(eq(social.getEmail()), anyString(), any(), any());
    then(mailSender).should(never()).send(eq(unknownEmail), anyString(), any(), any());
  }

  @Test
  @DisplayName("[S-A09] HTTP: 같은 이메일 가입 인증은 첫 예약부터 하루 5번까지, 6번째는 429 EMAIL_SEND_DAILY_LIMIT이고 메일 없음")
  void signupDailyLimitOverHttp() throws Exception {
    String email = "daily" + SEQ.incrementAndGet() + "@test.com";
    for (int i = 0; i < 5; i++) {
      assertThat(post("/api/v1/auth/email/send", "{\"email\":\"" + email + "\"}").statusCode()).isEqualTo(200);
      redisTemplate.delete("auth:email:cooldown:" + email);   // 60초 재발송 제한만 풀고 하루 횟수는 그대로
    }

    HttpResponse<String> sixth = post("/api/v1/auth/email/send", "{\"email\":\"" + email + "\"}");

    assertError(sixth, 429, MemberErrorCode.EMAIL_SEND_DAILY_LIMIT);
    then(mailSender).should(times(5)).send(eq(email), anyString(), any(), eq(EmailPurpose.SIGNUP));
  }

  @Test
  @DisplayName("[S-A09] HTTP: 재설정 발송은 하루 5번을 넘어도 같은 200 · 본문을 주고, 6번째는 메일을 보내지 않음")
  void resetDailyLimitKeepsSameResponse() throws Exception {
    Member member = saveMember();
    HttpResponse<String> first = sendReset(member.getEmail());
    for (int i = 1; i < 5; i++) {
      redisTemplate.delete("auth:email:cooldown:" + member.getEmail());
      assertThat(sendReset(member.getEmail()).statusCode()).isEqualTo(200);
    }
    redisTemplate.delete("auth:email:cooldown:" + member.getEmail());

    HttpResponse<String> sixth = sendReset(member.getEmail());

    assertThat(sixth.statusCode()).isEqualTo(200);
    assertThat(sixth.body()).isEqualTo(first.body());
    then(mailSender).should(times(5)).send(eq(member.getEmail()), anyString(), any(), eq(EmailPurpose.PASSWORD_RESET));
  }

  // ===== 도우미 =====

  // 재설정 인증번호를 요청하고, 메일로 보내려던 번호를 가로챈다
  private String sendResetCode(String email) throws Exception {
    assertThat(sendReset(email).statusCode()).isEqualTo(200);
    ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
    then(mailSender).should().send(eq(email), code.capture(), any(), eq(EmailPurpose.PASSWORD_RESET));
    return code.getValue();
  }

  private HttpResponse<String> sendReset(String email) throws Exception {
    return post("/api/v1/auth/email/send", "{\"email\":\"" + email + "\",\"purpose\":\"PASSWORD_RESET\"}");
  }

  private HttpResponse<String> verify(String email, String code, EmailPurpose purpose) throws Exception {
    return post("/api/v1/auth/email/verify",
        "{\"email\":\"" + email + "\",\"code\":\"" + code + "\",\"purpose\":\"" + purpose.name() + "\"}");
  }

  private HttpResponse<String> resetPassword(String token, String newPassword) throws Exception {
    return post("/api/v1/auth/passwordResets",
        "{\"resetToken\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}");
  }

  // 확인 응답에서 재설정 토큰을 꺼낸다 (확인이 실패했으면 여기서 테스트가 멈춤)
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

  private HttpResponse<String> reissueWithCookie(String refreshToken) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/reissue"))
        .header("Cookie", "refreshToken=" + refreshToken)
        .POST(HttpRequest.BodyPublishers.noBody())
        .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static void assertError(HttpResponse<String> response, int status, MemberErrorCode errorCode) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.body()).contains("\"code\":\"" + errorCode.name() + "\"");
  }

  private LoginResult login(Member member, String password) {
    return loginService.login(new LoginCommand(member.getEmail(), password));
  }

  private String passwordOf(Long memberId) {
    return jdbcTemplate.queryForObject("select password from member where id = ?", String.class, memberId);
  }

  private Member saveMember() {
    int n = SEQ.incrementAndGet();
    return memberRepository.save(Member.createLocal("reset" + n + "@test.com", passwordEncoder.encode(OLD_PASSWORD),
        "재설" + n, String.format("016%08d", n), "004", "v1:test-account", null, "홍길동"));
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

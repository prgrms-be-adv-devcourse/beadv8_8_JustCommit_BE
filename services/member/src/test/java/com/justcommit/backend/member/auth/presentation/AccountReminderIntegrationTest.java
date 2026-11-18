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
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * 가입 계정 안내 (#412)를 실제 PostgreSQL · Redis · HTTP로 확인한다.
 * - 유효한 요청은 가입 여부 · 로그인 방식 · 발송 제한 · SMTP 실패와 관계없이 같은 200 (입력 오류는 400)
 * - 60초 재발송 제한은 인증 메일과 공유하고, 하루 5회 한도는 용도별로 따로 센다 (#412 리뷰 반영).
 *   재설정 · 안내는 제한에 걸려도 200이므로, 응답이 아니라 대체한 메일 발송자의 호출로 실제 발송 여부를 확인한다
 * - IP 기준 제한은 Gateway에서 API별로 따로 센다 (Gateway 테스트에서 확인)
 * - 두 메일 클래스를 가짜로 바꿔 실제로 보내지 않는다
 * - 검증 범위: member 서비스(보안 필터 포함)까지이며 Gateway는 거치지 않는다 (IP 제한은 Gateway 테스트),
 *   메일 제목 · 본문은 메일 클래스 단위 테스트에서 본다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class AccountReminderIntegrationTest {

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final AtomicInteger SEQ = new AtomicInteger();

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

  @MockitoBean
  AccountReminderMailSender reminderMailSender;

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
  @DisplayName("1. 로컬 · 카카오 · 미가입 · 탈퇴 · 네이버 · 재발송 제한 중 · SMTP 실패 모두 같은 200과 본문, 메일은 지원하는 방식의 활동 회원에게만")
  void sameResponseForEveryCase() throws Exception {
    Member local = saveMember();
    String kakaoEmail = saveSocial(Provider.KAKAO);
    String naverEmail = saveSocial(Provider.NAVER);
    Member deleted = saveMember();
    jdbcTemplate.update("update member set status = 'DELETED' where id = ?", deleted.getId());
    Member smtpFail = saveMember();
    willThrow(new MailSendException("smtp down")).given(reminderMailSender).send(eq(smtpFail.getEmail()), any());
    String unknownEmail = "nobody" + SEQ.incrementAndGet() + "@test.com";

    // 경우 이름과 함께 모아, 실패하면 어느 경우인지 메시지에 나오게 한다 (넣은 순서대로 요청)
    Map<String, HttpResponse<String>> responses = new LinkedHashMap<>();
    responses.put("활동 중 로컬", remind(local.getEmail()));
    responses.put("활동 중 카카오", remind(kakaoEmail));
    responses.put("미가입", remind(unknownEmail));
    responses.put("탈퇴", remind(deleted.getEmail()));
    responses.put("네이버(지원 안 함)", remind(naverEmail));
    responses.put("재발송 제한 중", remind(local.getEmail()));
    responses.put("SMTP 실패", remind(smtpFail.getEmail()));

    HttpResponse<String> first = responses.get("활동 중 로컬");
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(first.body()).contains("가입된 계정이면 안내 메일을 보내드립니다.");
    responses.forEach((name, response) -> {
      assertThat(response.statusCode()).as(name).isEqualTo(first.statusCode());
      assertThat(response.body()).as(name).isEqualTo(first.body());
    });

    then(reminderMailSender).should(times(1)).send(local.getEmail(), Provider.LOCAL);
    then(reminderMailSender).should().send(kakaoEmail, Provider.KAKAO);
    then(reminderMailSender).should(never()).send(eq(unknownEmail), any());
    then(reminderMailSender).should(never()).send(eq(deleted.getEmail()), any());
    then(reminderMailSender).should(never()).send(eq(naverEmail), any());
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
  @DisplayName("4. 활동 중인 로컬 회원이 안내 하루 한도를 채워도 재설정 인증번호 메일은 발송된다 (안내 6번째는 발송 안 됨)")
  void reminderDailyLimitDoesNotBlockReset() throws Exception {
    Member local = saveMember();
    String email = local.getEmail();

    // 안내 요청 → 실제 발송 확인 → 쿨다운 제거를 5회 반복 (쿨다운이 아니라 하루 한도까지 쌓이게)
    for (int i = 1; i <= 5; i++) {
      assertThat(remind(email).statusCode()).isEqualTo(200);
      then(reminderMailSender).should(times(i)).send(email, Provider.LOCAL);
      clearCooldown(email);
    }

    // 같은 용도 6번째: 쿨다운이 없는 상태라 하루 한도로 막혀 발송되지 않는다 (응답은 같은 200)
    assertThat(remind(email).statusCode()).isEqualTo(200);
    then(reminderMailSender).should(times(5)).send(email, Provider.LOCAL);
    assertThat(redisTemplate.hasKey("auth:email:cooldown:" + email)).isFalse();

    assertThat(sendResetCode(email).statusCode()).isEqualTo(200);

    then(verificationMailSender).should().send(eq(email), anyString(), any(), eq(EmailPurpose.PASSWORD_RESET));
    assertThat(redisTemplate.opsForSet().size("auth:email:daily:account-reminder:" + email)).isEqualTo(5L);
    assertThat(redisTemplate.opsForSet().size("auth:email:daily:password-reset:" + email)).isEqualTo(1L);
  }

  @Test
  @DisplayName("4-2. 미가입 이메일로 안내 하루 한도를 채워도 가입 인증번호는 발송된다")
  void reminderDailyLimitDoesNotBlockSignup() throws Exception {
    String email = "daily" + SEQ.incrementAndGet() + "@test.com";

    // 미가입이라 안내 메일은 없지만 예약은 똑같이 쌓인다 (매번 쿨다운 제거)
    for (int i = 0; i < 5; i++) {
      assertThat(remind(email).statusCode()).isEqualTo(200);
      clearCooldown(email);
    }
    then(reminderMailSender).should(never()).send(eq(email), any());
    assertThat(redisTemplate.opsForSet().size("auth:email:daily:account-reminder:" + email)).isEqualTo(5L);

    assertThat(sendSignupCode(email).statusCode()).isEqualTo(200);

    then(verificationMailSender).should().send(eq(email), anyString(), any(), eq(EmailPurpose.SIGNUP));
    assertThat(redisTemplate.opsForSet().size("auth:email:daily:signup:" + email)).isEqualTo(1L);
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
    assertThat(redisTemplate.opsForSet().size("auth:email:daily:account-reminder:" + local.getEmail())).isEqualTo(1L);
  }

  // ===== 도우미 =====

  private HttpResponse<String> remind(String email) throws Exception {
    return post("/api/v1/auth/accountReminders", "{\"email\":\"" + email + "\"}");
  }

  private HttpResponse<String> sendSignupCode(String email) throws Exception {
    return post("/api/v1/auth/email/send", "{\"email\":\"" + email + "\"}");
  }

  private HttpResponse<String> sendResetCode(String email) throws Exception {
    return post("/api/v1/auth/email/send", "{\"email\":\"" + email + "\",\"purpose\":\"PASSWORD_RESET\"}");
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
        "안내" + n, String.format("014%08d", n), "004", "v1:test-account", null, "홍길동"));
  }

  // 소셜 회원: 로컬로 저장한 뒤 DB에서 비밀번호를 비우고 로그인 방식을 바꾼다 (비밀번호 변경 통합 테스트와 같은 방식)
  // 저장한 Member 객체는 여전히 LOCAL이라 돌려주지 않고, 테스트에 필요한 이메일만 돌려준다
  private String saveSocial(Provider provider) {
    Member member = saveMember();
    jdbcTemplate.update("update member set password = null, provider = ?, provider_id = ? where id = ?",
        provider.name(), provider.name().toLowerCase() + "-" + member.getId(), member.getId());
    return member.getEmail();
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

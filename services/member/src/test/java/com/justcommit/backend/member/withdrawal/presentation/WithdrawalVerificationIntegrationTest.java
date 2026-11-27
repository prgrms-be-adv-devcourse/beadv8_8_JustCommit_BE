package com.justcommit.backend.member.withdrawal.presentation;

import com.justcommit.backend.member.support.MemberIntegrationTest;
import com.justcommit.backend.common.security.TokenHash;
import com.justcommit.backend.member.auth.domain.EmailPurpose;
import com.justcommit.backend.member.auth.infrastructure.jwt.JwtProvider;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;

/**
 * 탈퇴 재확인 - 카카오 회원의 이메일 재인증 (#407, S-A10 일부)을 실제 HTTP 요청 · PostgreSQL · Redis로 확인한다.
 * 메일은 가짜 발송기로 받아 번호를 꺼내고, 메일 작업은 요청 스레드에서 바로 실행한다 (계좌 변경 테스트와 같은 방식).
 * 탈퇴 토큰의 실제 사용(다른 회원 · 인증 버전 변경 · 재사용 거절)은 최종 탈퇴 테스트에서 확인한다.
 */
class WithdrawalVerificationIntegrationTest extends MemberIntegrationTest {

  private static final AtomicInteger SEQ = new AtomicInteger();
  private static final String VERIFICATIONS = "/api/v1/users/me/withdrawal/verifications";
  private static final String TOKENS = "/api/v1/users/me/withdrawal/tokens";
  private static final Pattern WITHDRAWAL_TOKEN = Pattern.compile("\"withdrawalToken\":\"([^\"]+)\"");

  @Value("${local.server.port}")
  private int port;

  @Autowired MemberRepository memberRepository;
  @Autowired JwtProvider jwtProvider;
  @Autowired StringRedisTemplate redisTemplate;
  @Autowired JdbcTemplate jdbcTemplate;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from outbox_event");
    jdbcTemplate.update("delete from address");
    jdbcTemplate.update("delete from member");
    try (var connection = redisTemplate.getRequiredConnectionFactory().getConnection()) {
      connection.serverCommands().flushAll();
    }
  }

  @Test
  @DisplayName("1. 로그인 없이 호출하면 인증번호 발송 · 탈퇴 토큰 발급 모두 401")
  void requiresLogin() throws Exception {
    assertThat(post(VERIFICATIONS, null, "").statusCode()).isEqualTo(401);
    assertThat(post(TOKENS, null, codeBody("123456")).statusCode()).isEqualTo(401);
  }

  @Test
  @DisplayName("2. 카카오 회원: 발송 → 받은 번호로 확인하면 10분짜리 탈퇴 토큰, Redis에는 토큰 해시 키로 회원 id · 인증 버전")
  void sendAndIssueToken() throws Exception {
    Member member = saveKakaoMember();
    String accessToken = accessToken(member);

    HttpResponse<String> sent = post(VERIFICATIONS, accessToken, "");
    assertThat(sent.statusCode()).as(sent.body()).isEqualTo(200);
    assertThat(sent.body()).contains("\"expiresIn\":300", "\"resendAvailableIn\":60");

    HttpResponse<String> issued = post(TOKENS, accessToken, codeBody(sentCode(member.getEmail())));
    assertThat(issued.statusCode()).as(issued.body()).isEqualTo(200);
    assertThat(issued.body()).contains("\"expiresIn\":600");
    Matcher matcher = WITHDRAWAL_TOKEN.matcher(issued.body());
    assertThat(matcher.find()).isTrue();

    assertThat(redisTemplate.opsForValue().get("auth:withdrawal:" + TokenHash.sha256Hex(matcher.group(1))))
        .isEqualTo(member.getId() + ":" + member.getTokenVersion());
  }

  @Test
  @DisplayName("3. 로컬 회원은 발송 · 토큰 발급 모두 400 BAD_REQUEST_DATA, 메일을 보내지 않음 (비밀번호로 재확인)")
  void localMemberRejected() throws Exception {
    Member member = saveLocalMember();
    String accessToken = accessToken(member);

    HttpResponse<String> sent = post(VERIFICATIONS, accessToken, "");
    HttpResponse<String> issued = post(TOKENS, accessToken, codeBody("123456"));

    assertThat(sent.statusCode()).isEqualTo(400);
    assertThat(sent.body()).contains("\"code\":\"BAD_REQUEST_DATA\"");
    assertThat(issued.statusCode()).isEqualTo(400);
    assertThat(issued.body()).contains("\"code\":\"BAD_REQUEST_DATA\"");
    then(verificationMailSender).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("4. 틀린 번호면 400 EMAIL_CODE_MISMATCH, 탈퇴 토큰 없음")
  void wrongCode() throws Exception {
    Member member = saveKakaoMember();
    String accessToken = accessToken(member);
    post(VERIFICATIONS, accessToken, "");

    HttpResponse<String> response = post(TOKENS, accessToken, codeBody(wrongCodeOf(sentCode(member.getEmail()))));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("\"code\":\"EMAIL_CODE_MISMATCH\"");
    assertThat(redisTemplate.keys("auth:withdrawal:*")).isEmpty();
  }

  @Test
  @DisplayName("5. 5번째 오답이면 번호가 폐기돼, 그 뒤에는 맞는 번호도 거절되고 탈퇴 토큰 없음")
  void attemptsExceeded() throws Exception {
    Member member = saveKakaoMember();
    String accessToken = accessToken(member);
    post(VERIFICATIONS, accessToken, "");
    String code = sentCode(member.getEmail());
    String wrong = wrongCodeOf(code);

    for (int i = 0; i < 4; i++) {
      assertThat(post(TOKENS, accessToken, codeBody(wrong)).body()).contains("\"code\":\"EMAIL_CODE_MISMATCH\"");
    }
    assertThat(post(TOKENS, accessToken, codeBody(wrong)).body()).contains("\"code\":\"EMAIL_CODE_ATTEMPTS_EXCEEDED\"");

    HttpResponse<String> correct = post(TOKENS, accessToken, codeBody(code));
    assertThat(correct.statusCode()).isEqualTo(400);
    assertThat(redisTemplate.keys("auth:withdrawal:*")).isEmpty();
  }

  @Test
  @DisplayName("6. 발송 직후 다시 요청하면 429 EMAIL_SEND_COOLDOWN + Retry-After")
  void cooldown() throws Exception {
    Member member = saveKakaoMember();
    String accessToken = accessToken(member);
    post(VERIFICATIONS, accessToken, "");

    HttpResponse<String> again = post(VERIFICATIONS, accessToken, "");

    assertThat(again.statusCode()).isEqualTo(429);
    assertThat(again.body()).contains("\"code\":\"EMAIL_SEND_COOLDOWN\"");
    assertThat(again.headers().firstValue("Retry-After")).isPresent();
  }

  @Test
  @DisplayName("7. 번호를 받은 뒤 인증 버전이 바뀌면(비밀번호 변경 등) 맞는 번호여도 400 WITHDRAWAL_VERIFICATION_INVALID")
  void tokenVersionChangedAfterSend() throws Exception {
    Member member = saveKakaoMember();
    String accessToken = accessToken(member);
    post(VERIFICATIONS, accessToken, "");
    String code = sentCode(member.getEmail());
    jdbcTemplate.update("update member set token_version = token_version + 1 where id = ?", member.getId());

    HttpResponse<String> response = post(TOKENS, accessToken, codeBody(code));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("\"code\":\"WITHDRAWAL_VERIFICATION_INVALID\"");
    assertThat(redisTemplate.keys("auth:withdrawal:*")).isEmpty();
  }

  @Test
  @DisplayName("8. 다른 회원이 받은 번호로는 토큰을 받을 수 없음 (번호는 각자 이메일에 묶임)")
  void otherMembersCode() throws Exception {
    Member owner = saveKakaoMember();
    Member other = saveKakaoMember();
    post(VERIFICATIONS, accessToken(owner), "");

    HttpResponse<String> response = post(TOKENS, accessToken(other), codeBody(sentCode(owner.getEmail())));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(redisTemplate.keys("auth:withdrawal:*")).isEmpty();
  }

  @Test
  @DisplayName("9. [S-A10] 공개 인증 경로는 탈퇴 목적의 발송 · 확인을 거절 (400 EMAIL_PURPOSE_NOT_SUPPORTED)")
  void publicPathsRejectWithdrawal() throws Exception {
    HttpResponse<String> send = post("/api/v1/auth/email/send", null,
        "{\"email\":\"public@test.com\",\"purpose\":\"WITHDRAWAL\"}");
    HttpResponse<String> verify = post("/api/v1/auth/email/verify", null,
        "{\"email\":\"public@test.com\",\"code\":\"123456\",\"purpose\":\"WITHDRAWAL\"}");

    assertThat(send.statusCode()).isEqualTo(400);
    assertThat(send.body()).contains("\"code\":\"EMAIL_PURPOSE_NOT_SUPPORTED\"");
    assertThat(verify.statusCode()).isEqualTo(400);
    assertThat(verify.body()).contains("\"code\":\"EMAIL_PURPOSE_NOT_SUPPORTED\"");
    then(verificationMailSender).shouldHaveNoInteractions();
  }

  private String sentCode(String email) {
    ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
    then(verificationMailSender).should().send(eq(email), code.capture(), any(), eq(EmailPurpose.WITHDRAWAL));
    return code.getValue();
  }

  private static String wrongCodeOf(String code) {
    return code.equals("000000") ? "111111" : "000000";
  }

  private static String codeBody(String code) {
    return "{\"code\":\"" + code + "\"}";
  }

  // 카카오 회원은 비밀번호 로그인이 없어 Access 토큰을 직접 만든다 (member는 서명 · 만료만 확인)
  private String accessToken(Member member) {
    return jwtProvider.createAccessToken(member.getId(), "MEMBER", Instant.now());
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

  private Member saveKakaoMember() {
    int n = SEQ.incrementAndGet();
    return memberRepository.save(Member.createSocial(Provider.KAKAO, "kakao-" + n, "kakao" + n + "@test.com",
        "카카오" + n, String.format("017%08d", n), "004", "v1:test-account", null, "홍길동"));
  }

  private Member saveLocalMember() {
    int n = SEQ.incrementAndGet();
    return memberRepository.save(Member.createLocal("local" + n + "@test.com", "encoded-password",
        "로컬" + n, String.format("018%08d", n), "004", "v1:test-account", null, "홍길동"));
  }
}

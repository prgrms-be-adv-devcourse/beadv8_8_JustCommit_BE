package com.justcommit.backend.member.member.presentation.internal;

import com.justcommit.backend.member.auth.infrastructure.jwt.JwtProvider;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.infrastructure.crypto.AccountCipher;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계좌 조회 내부 API를 실제 HTTP로 호출해 member의 전체 보안 필터(JWT 필터 → 내부 키 필터 → SecurityConfig)와
 * 계좌 복호화를 확인한다 (#420).
 * 네트워크 수준 차단(포트 비공개 · Gateway 미라우팅)과 응답 본문 로깅 여부는 이 테스트 범위가 아니다.
 * 준비 데이터는 요청 전에 커밋해야 서버가 볼 수 있어 테스트에 @Transactional을 붙이지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class MemberInternalApiIntegrationTest {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final String INTERNAL_KEY = "test-internal-key";
  private static final String ACCOUNT_NO = "12345678901234";
  private static final AtomicInteger SEQ = new AtomicInteger();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
    registry.add("internal-api.key", () -> INTERNAL_KEY);
  }

  @Value("${local.server.port}")
  private int port;

  @Autowired
  private MemberRepository memberRepository;

  @Autowired
  private AccountCipher accountCipher;

  @Autowired
  private JwtProvider jwtProvider;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Test
  @DisplayName("내부 키 없이 부르면 401")
  void noInternalKey() throws Exception {
    Long memberId = saveMember();

    assertThat(getAccount(memberId, null, null).statusCode()).isEqualTo(401);
  }

  @Test
  @DisplayName("틀린 내부 키로 부르면 401")
  void wrongInternalKey() throws Exception {
    Long memberId = saveMember();

    assertThat(getAccount(memberId, "wrong-key", null).statusCode()).isEqualTo(401);
  }

  @Test
  @DisplayName("유효한 회원 Access 토큰만 있고 내부 키가 없으면 401 (로그인한 회원도 부를 수 없음)")
  void memberTokenWithoutInternalKey() throws Exception {
    Long memberId = saveMember();
    String accessToken = jwtProvider.createAccessToken(memberId, "MEMBER");

    // 같은 토큰으로 회원 API가 200 → 토큰 자체는 유효함 (아래 401은 토큰 문제가 아님)
    HttpRequest me = HttpRequest.newBuilder(uri("/api/v1/users/me"))
        .header("Authorization", "Bearer " + accessToken)
        .GET().build();
    assertThat(httpClient.send(me, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);

    assertThat(getAccount(memberId, null, accessToken).statusCode()).isEqualTo(401);
  }

  @Test
  @DisplayName("맞는 내부 키만으로 200, 은행 코드 · 복호화한 계좌번호 · 예금주를 돌려준다")
  void success() throws Exception {
    Long memberId = saveMember();

    HttpResponse<String> response = getAccount(memberId, INTERNAL_KEY, null);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains("\"bankCode\":\"004\"")
        .contains("\"accountNo\":\"" + ACCOUNT_NO + "\"")
        .contains("\"accountHolder\":\"홍길동\"");
  }

  @Test
  @DisplayName("없는 회원이면 404 MEMBER_NOT_FOUND")
  void memberNotFound() throws Exception {
    HttpResponse<String> response = getAccount(999_999L, INTERNAL_KEY, null);

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).contains("\"code\":\"MEMBER_NOT_FOUND\"");
  }

  @Test
  @DisplayName("탈퇴 회원이면 계좌를 주지 않고 404 MEMBER_NOT_FOUND")
  void deletedMember() throws Exception {
    Long memberId = saveMember();
    // 탈퇴 기능(#407) 전이라 DB 상태만 바꿈 (자동 커밋)
    jdbcTemplate.update("update member set status = 'DELETED' where id = ?", memberId);

    HttpResponse<String> response = getAccount(memberId, INTERNAL_KEY, null);

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).contains("\"code\":\"MEMBER_NOT_FOUND\"");
  }

  // 계좌번호를 실제 AccountCipher로 암호화해 저장 (save는 자기 트랜잭션으로 커밋)
  private Long saveMember() {
    int n = SEQ.incrementAndGet();
    Member member = Member.createLocal("account" + n + "@test.com", "encoded", "acct" + n,
        "010" + String.format("%08d", n), "004", accountCipher.encrypt(ACCOUNT_NO), "홍길동");
    return memberRepository.save(member).getId();
  }

  private HttpResponse<String> getAccount(Long memberId, String internalKey, String accessToken) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri("/internal/v1/members/" + memberId + "/account")).GET();
    if (internalKey != null) {
      builder.header("X-Internal-Key", internalKey);
    }
    if (accessToken != null) {
      builder.header("Authorization", "Bearer " + accessToken);
    }
    return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
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

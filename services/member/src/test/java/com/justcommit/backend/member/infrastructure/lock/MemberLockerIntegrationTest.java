package com.justcommit.backend.member.infrastructure.lock;

import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
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

/**
 * 회원 잠금 대기 제한 (#409)을 실제 PostgreSQL · Redis와 HTTP로 확인한다.
 * - 다른 트랜잭션이 회원 행을 잠근 동안 로그인하면 대기 제한 뒤 409 MEMBER_BUSY
 * - 배송지 추가 · 판매자 등록 · 닉네임 변경도 같은 잠금을 써서 409 MEMBER_BUSY, 데이터와 Outbox가 남지 않음 (#419)
 * - 대기 제한은 테스트에서 500ms로 줄인다: 빨리 끝내고, 기본값(3초)이 아니라 설정값이 쓰이는지도 함께 확인
 * - 준비 데이터는 요청 전에 커밋해야 서버가 볼 수 있어 테스트에 @Transactional을 붙이지 않는다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class MemberLockerIntegrationTest {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final Duration LOCK_TIMEOUT = Duration.ofMillis(500);
  private static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration WAIT = Duration.ofSeconds(10);
  private static final String PASSWORD = "plant1234";
  private static final AtomicInteger SEQ = new AtomicInteger();
  private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");
  private static final String ADDRESSES = "/api/v1/users/me/addresses";
  private static final String SELLERS = "/api/v1/sellers";

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("member.lock.timeout", () -> LOCK_TIMEOUT.toMillis() + "ms");
  }

  @Value("${local.server.port}")
  private int port;

  @Autowired
  private MemberRepository memberRepository;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private MemberLocker memberLocker;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private ExecutorService executor;

  @BeforeEach
  void setUp() {
    executor = Executors.newSingleThreadExecutor();
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    executor.shutdownNow();
    executor.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS);
  }

  @Test
  @DisplayName("다른 트랜잭션이 회원 행을 잠근 동안 로그인하면 대기 제한 뒤 409 MEMBER_BUSY, 잠금이 풀리면 200")
  void loginWhileMemberLocked() throws Exception {
    Member member = localMember();
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    // 다른 트랜잭션: 회원 행을 잠근 채 풀어 줄 때까지 붙잡음
    Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
      memberRepository.findByIdForUpdate(member.getId()).orElseThrow();
      locked.countDown();
      try {
        release.await(WAIT.toSeconds(), TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }));

    HttpResponse<String> busy;
    Duration elapsed;
    try {
      assertThat(locked.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
      long start = System.nanoTime();
      busy = login(member.getEmail());
      elapsed = Duration.ofNanos(System.nanoTime() - start);
    } finally {
      release.countDown();
    }
    holder.get(WAIT.toSeconds(), TimeUnit.SECONDS);

    assertThat(busy.statusCode()).isEqualTo(409);
    assertThat(busy.body()).contains("\"code\":\"MEMBER_BUSY\"");
    // 바로 실패하지 않고 대기 제한만큼 기다렸고, 기본값(3초)이 아니라 설정값으로 끝남
    assertThat(elapsed).isGreaterThanOrEqualTo(LOCK_TIMEOUT).isLessThan(DEFAULT_LOCK_TIMEOUT);

    // 잠금이 풀리면 같은 요청은 성공 → 위 409는 잠금 때문
    assertThat(login(member.getEmail()).statusCode()).isEqualTo(200);
  }

  @Test
  @DisplayName("판매자가 배송지를 추가할 때 회원 행이 잠겨 있으면 409 MEMBER_BUSY, 배송지와 Outbox는 그대로")
  void addAddressWhileMemberLocked() throws Exception {
    Member member = localMember();
    String token = accessToken(member.getEmail());
    // 판매자여야 성공 시 MemberAddressChanged가 발행됨 → 실패 시 이벤트까지 남지 않는지 확인할 의미가 있음
    assertCreated(send("POST", ADDRESSES, token, addressBody(true)));
    assertCreated(send("POST", SELLERS, token, "{}"));
    long addressBefore = addressCount(member.getId());
    long outboxBefore = outboxCount(member.getId());

    HttpResponse<String> response = whileMemberLocked(member.getId(),
        () -> send("POST", ADDRESSES, token, addressBody(false)));

    assertBusy(response);
    assertThat(addressCount(member.getId())).isEqualTo(addressBefore);
    assertThat(outboxCount(member.getId())).isEqualTo(outboxBefore);
  }

  @Test
  @DisplayName("판매자 등록할 때 회원 행이 잠겨 있으면 409 MEMBER_BUSY, 판매자 행 · 권한 · Outbox는 그대로")
  void registerSellerWhileMemberLocked() throws Exception {
    Member member = localMember();
    String token = accessToken(member.getEmail());
    // 기본 배송지가 있어 잠금만 없으면 등록에 성공할 수 있는 상태
    assertCreated(send("POST", ADDRESSES, token, addressBody(true)));
    long outboxBefore = outboxCount(member.getId());

    HttpResponse<String> response = whileMemberLocked(member.getId(), () -> send("POST", SELLERS, token, "{}"));

    assertBusy(response);
    assertThat(count("select count(*) from seller where member_id = ?", member.getId())).isZero();
    assertThat(jdbcTemplate.queryForObject("select role from member where id = ?", String.class, member.getId()))
        .isEqualTo("MEMBER");
    assertThat(outboxCount(member.getId())).isEqualTo(outboxBefore);
  }

  @Test
  @DisplayName("닉네임을 바꿀 때 회원 행이 잠겨 있으면 409 MEMBER_BUSY, 닉네임과 Outbox는 그대로")
  void changeNicknameWhileMemberLocked() throws Exception {
    Member member = localMember();
    String token = accessToken(member.getEmail());
    long outboxBefore = outboxCount(member.getId());

    HttpResponse<String> response = whileMemberLocked(member.getId(),
        () -> send("PATCH", "/api/v1/users/me/nickname", token, "{\"nickname\":\"변경닉네임\"}"));

    assertBusy(response);
    assertThat(jdbcTemplate.queryForObject("select nickname from member where id = ?", String.class, member.getId()))
        .isEqualTo(member.getNickname());
    assertThat(outboxCount(member.getId())).isEqualTo(outboxBefore);
  }

  @Test
  @DisplayName("잠금 조회가 정상 반환되면 lock_timeout은 이전 값으로 돌아가, 이후 저장 쿼리에는 기존 lock_timeout이 적용됨")
  void restoresLockTimeoutAfterLock() {
    Member member = localMember();

    String after = transactionTemplate.execute(status -> {
      // 0(제한 없음)이 아닌 값을 먼저 걸어, "0으로 초기화"가 아니라 "이전 값 복원"인지 확인
      jdbcTemplate.queryForObject("select set_config('lock_timeout', '7s', true)", String.class);
      memberLocker.lockById(member.getId()).orElseThrow();
      return jdbcTemplate.queryForObject("select current_setting('lock_timeout')", String.class);
    });

    assertThat(after).isEqualTo("7s");
  }

  // 다른 트랜잭션이 회원 행을 실제로 잠근 뒤 요청을 보낸다. 요청이 끝나면 잠금을 풀고 그 트랜잭션이 끝날 때까지 기다린다
  private HttpResponse<String> whileMemberLocked(Long memberId, Callable<HttpResponse<String>> request) throws Exception {
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
      memberRepository.findByIdForUpdate(memberId).orElseThrow();
      locked.countDown();
      try {
        release.await(WAIT.toSeconds(), TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }));

    try {
      assertThat(locked.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
      return request.call();
    } finally {
      release.countDown();
      holder.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    }
  }

  // 대기 시간(설정값 500ms 적용)은 로그인 테스트가 확인하므로 여기서는 응답 계약만 본다
  private static void assertBusy(HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(response.body()).contains("\"code\":\"MEMBER_BUSY\"");
  }

  private static void assertCreated(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
  }

  // 잠금을 잡기 전에 로그인해서 Access 토큰을 받는다 (로그인도 회원 행을 잠그므로)
  private String accessToken(String email) throws Exception {
    HttpResponse<String> response = login(email);
    assertThat(response.statusCode()).isEqualTo(200);
    Matcher matcher = ACCESS_TOKEN.matcher(response.body());
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }

  private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + token)
        .method(method, HttpRequest.BodyPublishers.ofString(body))
        .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String addressBody(boolean isDefault) {
    return "{\"recipientName\":\"홍길동\",\"recipientPhone\":\"01012345678\",\"zipcode\":\"06236\","
        + "\"address1\":\"서울 강남구 테헤란로 1\",\"isDefault\":" + isDefault + "}";
  }

  private long addressCount(Long memberId) {
    return count("select count(*) from address where member_id = ?", memberId);
  }

  private long outboxCount(Long memberId) {
    return count("select count(*) from outbox_event where aggregate_id = ?", String.valueOf(memberId));
  }

  private long count(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, Long.class, args);
  }

  private HttpResponse<String> login(String email) throws Exception {
    String body = "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private Member localMember() {
    int n = SEQ.incrementAndGet();
    return transactionTemplate.execute(status -> memberRepository.save(Member.createLocal(
        "lock" + n + "@test.com", passwordEncoder.encode(PASSWORD), "잠금" + n, String.format("012%08d", n),
        "004", "v1:test-account", null, "홍길동")));
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

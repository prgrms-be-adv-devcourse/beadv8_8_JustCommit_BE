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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회원 잠금 대기 제한 (#409)을 실제 PostgreSQL · Redis와 HTTP로 확인한다.
 * - 다른 트랜잭션이 회원 행을 잠근 동안 로그인하면 대기 제한 뒤 409 MEMBER_BUSY
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
        "004", "v1:test-account", "홍길동")));
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

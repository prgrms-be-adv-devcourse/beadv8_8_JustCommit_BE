package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.infrastructure.redis.RefreshTokenRedisStore;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * 회원 행 잠금으로 토큰 발급과 폐기(비밀번호 변경 · 탈퇴)가 줄 서는지 실제 PostgreSQL · Redis로 확인한다 (#409).
 * - 순서를 고정한다: 한쪽이 잠금을 쥔 채 멈추면, 다른 쪽이 "그 세션에" 막혀 기다리는지 pg_blocking_pids()로 확인한 뒤 푼다
 * - 폐기는 공통 처리가 아직 없어 테스트 안에서 잠금 → 변경(인증 버전 · 비밀번호 · 상태) → 커밋으로 흉내 낸다
 * - 서비스를 직접 부르므로 HTTP 상태가 아니라 BusinessException의 에러 코드로 확인한다
 * - 테스트 클래스에 @Transactional을 붙이지 않는다: 준비 데이터는 먼저 커밋하고, 작업 스레드마다 별도 트랜잭션
 * - 범위 밖: Access 토큰 거절(Gateway · Redis 폐기 기록), 잠금 대기 시간 제한
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class TokenIssueRevocationRaceIntegrationTest {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  private static final String PASSWORD = "plant1234";
  private static final String NEW_PASSWORD = "newplant1234";
  private static final Duration WAIT = Duration.ofSeconds(10);
  private static final AtomicInteger SEQ = new AtomicInteger();

  @MockitoSpyBean
  private DatabaseClock databaseClock;

  @MockitoSpyBean
  private RefreshTokenRedisStore refreshTokenStore;

  @Autowired
  private LoginService loginService;

  @Autowired
  private SocialLoginService socialLoginService;

  @Autowired
  private TokenReissueService tokenReissueService;

  @Autowired
  private MemberRepository memberRepository;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private StringRedisTemplate redisTemplate;

  private ExecutorService executor;
  private final List<PauseGate> gates = new CopyOnWriteArrayList<>();
  // DatabaseClock.now()에서 멈출 대기 장치 (발급 경로는 잠금 · 검사 뒤, Refresh 저장 전에 이 메서드를 부름)
  private volatile PauseGate clockGate;
  // findOwner()가 값을 읽은 뒤 두 재발급 요청을 맞추는 장치
  private volatile CyclicBarrier ownerBarrier;
  private volatile Set<String> ownerBarrierThreads = Set.of();

  @BeforeEach
  void setUp() {
    flushRedis();
    executor = Executors.newCachedThreadPool();
    gates.clear();
    clockGate = null;
    ownerBarrier = null;
    ownerBarrierThreads = Set.of();

    // 지정한 작업만 멈추고 나머지는 실제 메서드 (스파이는 테스트마다 초기화되므로 매번 다시 설정)
    doAnswer(invocation -> {
      PauseGate gate = clockGate;
      if (gate != null) {
        gate.pauseIfTarget(this::currentBackendPid);
      }
      return invocation.callRealMethod();
    }).when(databaseClock).now();

    doAnswer(invocation -> {
      Object owner = invocation.callRealMethod();
      CyclicBarrier barrier = ownerBarrier;
      if (barrier != null && ownerBarrierThreads.contains(Thread.currentThread().getName())) {
        barrier.await(WAIT.toSeconds(), TimeUnit.SECONDS);
      }
      return owner;
    }).when(refreshTokenStore).findOwner(anyString());
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    // 실패해도 해제 신호 → 작업 종료 확인 → 데이터 정리 순서
    gates.forEach(PauseGate::release);
    CyclicBarrier barrier = ownerBarrier;
    if (barrier != null) {
      barrier.reset();
    }
    executor.shutdown();
    if (!executor.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS)) {
      executor.shutdownNow();
      executor.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS);
    }
    flushRedis();
  }

  // ── 발급이 먼저 잠금 ──

  @Test
  @DisplayName("발급이 먼저 잠금: 폐기는 로그인 세션에 막혀 기다리고, 로그인이 받은 옛 버전 Refresh는 폐기 뒤 재발급할 수 없다")
  void issuanceLocksFirst() throws Exception {
    Member member = localMember();
    clockGate = new PauseGate("login");

    Future<LoginResult> login = run("login", () -> loginService.login(new LoginCommand(member.getEmail(), PASSWORD)));
    int loginPid = clockGate.awaitReached();  // 로그인이 잠금 · 검사를 마치고 발급 직전에 멈춤

    Future<Object> revoke = revokeAsync(member.getId(), null, versionOnly());
    awaitBlockedBy(loginPid);  // 폐기가 로그인 세션에 막혀 기다림

    clockGate.release();
    LoginResult issued = result(login);
    result(revoke);

    assertThat(tokenVersionOf(member.getId())).isEqualTo(1);
    assertThatThrownBy(() -> tokenReissueService.reissue(issued.refreshToken()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(MemberErrorCode.INVALID_REFRESH_TOKEN);
  }

  // ── 폐기가 먼저 잠금 ──

  @Test
  @DisplayName("폐기가 먼저 잠금(인증 버전만): 재발급은 폐기 세션에 막혀 기다린 뒤 INVALID_REFRESH_TOKEN")
  void revocationFirst_versionOnly_reissueRejected() throws Exception {
    Member member = localMember();
    String refreshToken = loginService.login(new LoginCommand(member.getEmail(), PASSWORD)).refreshToken();

    Future<TokenReissueResult> reissue = runWhileRevocationHoldsLock(member.getId(), versionOnly(),
        "reissue", () -> tokenReissueService.reissue(refreshToken));

    assertRejected(reissue, MemberErrorCode.INVALID_REFRESH_TOKEN);
  }

  @Test
  @DisplayName("폐기가 먼저 잠금(비밀번호 변경): 옛 비밀번호 로그인은 기다린 뒤 새 해시로 확인해 LOGIN_FAILED")
  void revocationFirst_passwordChanged_oldPasswordLoginRejected() throws Exception {
    Member member = localMember();

    Future<LoginResult> login = runWhileRevocationHoldsLock(member.getId(), passwordChanged(),
        "login", () -> loginService.login(new LoginCommand(member.getEmail(), PASSWORD)));

    assertRejected(login, MemberErrorCode.LOGIN_FAILED);
    // 변경이 실제로 반영됐는지 (새 비밀번호로는 로그인됨)
    assertThat(loginService.login(new LoginCommand(member.getEmail(), NEW_PASSWORD)).refreshToken()).isNotBlank();
  }

  @Test
  @DisplayName("폐기가 먼저 잠금(탈퇴): 맞는 비밀번호 로그인도 기다린 뒤 LOGIN_FAILED")
  void revocationFirst_withdrawn_loginRejected() throws Exception {
    Member member = localMember();

    Future<LoginResult> login = runWhileRevocationHoldsLock(member.getId(), withdrawn(),
        "login", () -> loginService.login(new LoginCommand(member.getEmail(), PASSWORD)));

    assertRejected(login, MemberErrorCode.LOGIN_FAILED);
  }

  @Test
  @DisplayName("폐기가 먼저 잠금(탈퇴): 재발급은 기다린 뒤 INVALID_REFRESH_TOKEN")
  void revocationFirst_withdrawn_reissueRejected() throws Exception {
    Member member = localMember();
    String refreshToken = loginService.login(new LoginCommand(member.getEmail(), PASSWORD)).refreshToken();

    Future<TokenReissueResult> reissue = runWhileRevocationHoldsLock(member.getId(), withdrawn(),
        "reissue", () -> tokenReissueService.reissue(refreshToken));

    assertRejected(reissue, MemberErrorCode.INVALID_REFRESH_TOKEN);
  }

  @Test
  @DisplayName("폐기가 먼저 잠금(탈퇴): 기존 소셜 회원 로그인은 기다린 뒤 SOCIAL_LOGIN_FAILED")
  void revocationFirst_withdrawn_socialLoginRejected() throws Exception {
    Member member = socialMember();

    Future<SocialLoginResult> login = runWhileRevocationHoldsLock(member.getId(), withdrawn(),
        "social-login", () -> socialLoginService.login(
            new SocialLoginCommand(Provider.KAKAO, member.getProviderId(), member.getEmail())));

    assertRejected(login, MemberErrorCode.SOCIAL_LOGIN_FAILED);
  }

  // ── 같은 Refresh 동시 재발급 ──

  @Test
  @DisplayName("같은 Refresh 재사용(기본 검증): 첫 요청이 발급 직전에 멈춘 동안 두 번째 요청이 와도 하나만 성공")
  void sameRefresh_onlyOneSucceeds() throws Exception {
    Member member = localMember();
    String refreshToken = loginService.login(new LoginCommand(member.getEmail(), PASSWORD)).refreshToken();
    clockGate = new PauseGate("reissue-1");

    Future<TokenReissueResult> first = run("reissue-1", () -> tokenReissueService.reissue(refreshToken));
    clockGate.awaitReached();  // 첫 요청: 잠금 → 꺼내며 삭제 → 확인을 마치고 멈춤

    // 첫 요청이 이미 꺼냈으므로 두 번째 요청은 잠금 전에 거절될 수 있음 (정상 동작)
    Future<TokenReissueResult> second = run("reissue-2", () -> tokenReissueService.reissue(refreshToken));
    assertRejected(second, MemberErrorCode.INVALID_REFRESH_TOKEN);

    clockGate.release();
    assertThat(result(first).refreshToken()).isNotBlank();
  }

  @Test
  @DisplayName("같은 Refresh 재사용(경쟁 검증): 두 요청 모두 회원을 식별한 뒤 잠금 · 꺼내며 삭제를 거쳐 하나만 성공")
  void sameRefresh_bothIdentified_onlyOneSucceeds() throws Exception {
    Member member = localMember();
    String refreshToken = loginService.login(new LoginCommand(member.getEmail(), PASSWORD)).refreshToken();
    // 두 요청이 모두 Refresh를 읽은(회원 식별) 뒤에야 잠금으로 넘어가게 맞춤
    ownerBarrierThreads = Set.of("reissue-1", "reissue-2");
    ownerBarrier = new CyclicBarrier(2);
    // 먼저 잠금을 잡은 쪽만 발급 직전에 멈춤
    clockGate = new PauseGate("reissue-1", "reissue-2");

    Future<TokenReissueResult> a = run("reissue-1", () -> tokenReissueService.reissue(refreshToken));
    Future<TokenReissueResult> b = run("reissue-2", () -> tokenReissueService.reissue(refreshToken));
    int holderPid = clockGate.awaitReached();
    awaitBlockedBy(holderPid);  // 다른 요청은 회원 잠금을 기다림

    clockGate.release();
    int succeeded = 0;
    int rejected = 0;
    for (Future<TokenReissueResult> future : List.of(a, b)) {
      try {
        assertThat(result(future).refreshToken()).isNotBlank();
        succeeded++;
      } catch (ExecutionException e) {
        assertThat(e.getCause()).isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_REFRESH_TOKEN);
        rejected++;
      }
    }
    assertThat(succeeded).isEqualTo(1);
    assertThat(rejected).isEqualTo(1);
  }

  // ── 테스트용 폐기 (공통 처리 대신) ──

  // 폐기가 잠금을 쥔 채 멈춘 동안 task를 시작하고, task가 그 세션에 막힌 것을 확인한 뒤 폐기를 커밋
  private <T> Future<T> runWhileRevocationHoldsLock(Long memberId, Consumer<Member> change,
                                                    String name, Callable<T> task) throws Exception {
    PauseGate revokeGate = new PauseGate("revoke");
    Future<Object> revoke = revokeAsync(memberId, revokeGate, change);
    int revokePid = revokeGate.awaitReached();

    Future<T> future = run(name, task);
    awaitBlockedBy(revokePid);

    revokeGate.release();
    result(revoke);
    return future;
  }

  // 잠금 → (대기 장치) → 변경 → 커밋
  private Future<Object> revokeAsync(Long memberId, PauseGate gate, Consumer<Member> change) {
    return run("revoke", () -> {
      transactionTemplate.executeWithoutResult(status -> {
        Member member = memberRepository.findByIdForUpdate(memberId).orElseThrow();
        if (gate != null) {
          try {
            gate.pauseIfTarget(this::currentBackendPid);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
        }
        change.accept(member);
      });
      return null;
    });
  }

  private static Consumer<Member> versionOnly() {
    return Member::increaseTokenVersion;
  }

  private Consumer<Member> passwordChanged() {
    return member -> {
      ReflectionTestUtils.setField(member, "password", passwordEncoder.encode(NEW_PASSWORD));
      member.increaseTokenVersion();
    };
  }

  private static Consumer<Member> withdrawn() {
    return member -> {
      ReflectionTestUtils.setField(member, "status", MemberStatus.DELETED);
      member.increaseTokenVersion();
    };
  }

  // ── 순서 확인 도구 ──

  // holderPid 세션에 막혀 잠금을 기다리는 세션이 생길 때까지 확인 (아무 세션의 잠금 대기가 아니라 이 세션에 막힌 것만)
  private void awaitBlockedBy(int holderPid) throws InterruptedException {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (System.nanoTime() < deadline) {
      Integer blocked = jdbcTemplate.queryForObject(
          "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and ? = any(pg_blocking_pids(pid))",
          Integer.class, holderPid);
      if (blocked != null && blocked > 0) {
        return;
      }
      Thread.sleep(20);
    }
    fail("세션 %d에 막혀 기다리는 세션이 없습니다.", holderPid);
  }

  private int currentBackendPid() {
    Integer pid = jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class);
    return pid == null ? -1 : pid;
  }

  // 지정한 작업(스레드 이름)의 첫 호출 한 번만 멈추는 장치. 멈출 때 그 작업의 DB 세션 번호를 남긴다
  private final class PauseGate {
    private final Set<String> targets;
    private final AtomicBoolean used = new AtomicBoolean();
    private final CountDownLatch reached = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private volatile int holderPid;

    PauseGate(String... targets) {
      this.targets = Set.of(targets);
      gates.add(this);
    }

    void pauseIfTarget(IntSupplier pidOfCurrentSession) throws InterruptedException {
      if (!targets.contains(Thread.currentThread().getName()) || !used.compareAndSet(false, true)) {
        return;
      }
      holderPid = pidOfCurrentSession.getAsInt();
      reached.countDown();
      if (!released.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
        throw new IllegalStateException("해제 신호가 오지 않았습니다.");
      }
    }

    int awaitReached() throws InterruptedException {
      assertThat(reached.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("대기 지점 도착").isTrue();
      return holderPid;
    }

    void release() {
      released.countDown();
    }
  }

  // ── 준비 · 실행 도구 ──

  private <T> Future<T> run(String name, Callable<T> task) {
    return executor.submit(() -> {
      Thread.currentThread().setName(name);
      return task.call();
    });
  }

  private static <T> T result(Future<T> future) throws Exception {
    return future.get(WAIT.toSeconds(), TimeUnit.SECONDS);
  }

  private static void assertRejected(Future<?> future, MemberErrorCode errorCode) {
    assertThatThrownBy(() -> result(future))
        .isInstanceOf(ExecutionException.class)
        .satisfies(e -> assertThat(e.getCause())
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(errorCode));
  }

  private int tokenVersionOf(Long memberId) {
    return memberRepository.findById(memberId).orElseThrow().getTokenVersion();
  }

  // 준비 데이터는 먼저 커밋 (테스트마다 이메일 · 닉네임 · 휴대폰을 달리 해 UNIQUE 충돌 없게)
  private Member localMember() {
    int n = SEQ.incrementAndGet();
    return transactionTemplate.execute(status -> memberRepository.save(Member.createLocal(
        "race" + n + "@test.com", passwordEncoder.encode(PASSWORD), "경쟁" + n, String.format("010%08d", n),
        "004", "v1:test-account", "홍길동")));
  }

  private Member socialMember() {
    int n = SEQ.incrementAndGet();
    return transactionTemplate.execute(status -> memberRepository.save(Member.createSocial(
        Provider.KAKAO, "kakao-" + n, "social" + n + "@test.com", "소셜" + n, String.format("011%08d", n),
        "004", "v1:test-account", "홍길동")));
  }

  private void flushRedis() {
    try (var connection = redisTemplate.getRequiredConnectionFactory().getConnection()) {
      connection.serverCommands().flushAll();
    }
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

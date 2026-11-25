package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.security.JwtVerifier;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.common.testcontainers.SharedRedis;
import com.justcommit.backend.member.auth.domain.RevocationReason;
import com.justcommit.backend.member.auth.infrastructure.redis.RefreshTokenRedisStore;
import com.justcommit.backend.member.auth.infrastructure.repository.TokenRevocationRepository;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

/**
 * 폐기와 같은 초에 발급한 Access (#546).
 * Gateway는 iat <= 끊은 시각인 Access를 거절하므로, 폐기 직후 같은 초에 발급하면 정상 발급한 Access도 거절된다.
 * 로컬 로그인 · 재발급이 다음 초까지 기다린 뒤 발급하는지 확인한다 (카카오 재연결은 KakaoReloginIntegrationTest).
 * 같은 초는 DatabaseClock을 끊은 시각의 0.5초 뒤로 고정해 만든다.
 *
 * 대기 중 개입(추가 폐기 · 탈퇴 · 같은 Refresh 동시 재발급)은 발급 요청을 별도 스레드에서 실행해 확인한다:
 * 발급 스레드만 시각을 고정하고, 그 스레드가 첫 트랜잭션을 끝내고 대기에 들어간 뒤에 개입한다.
 * 개입(폐기)은 같은 회원 행을 잠그므로, 막히지 않고 끝나면 발급 쪽 잠금이 풀려 있었다는 뜻이다.
 * 테스트 클래스에 @Transactional을 붙이지 않는다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class SameSecondAccessIssueIntegrationTest {

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final String PASSWORD = "plant1234";
  private static final String REFRESH_TOKEN = "refresh-after-revocation";
  private static final Duration WAIT = Duration.ofSeconds(10);
  // 이 이름으로 시작하는 스레드만 fixIssueThreadClock의 고정 시각을 받는다
  private static final String ISSUE_THREAD_PREFIX = "issue-";
  private static final AtomicInteger SEQ = new AtomicInteger();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    SharedPostgres.register(registry);
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
    SharedRedis.register(registry);
  }

  @Autowired
  private TokenRevocationService revocationService;

  @Autowired
  private TokenRevocationRepository revocationRepository;

  @Autowired
  private LoginService loginService;

  @Autowired
  private SocialLoginService socialLoginService;

  @Autowired
  private TokenReissueService tokenReissueService;

  @Autowired
  private RefreshTokenRedisStore refreshTokenRedisStore;

  // Gateway와 같은 검증기로 iat(초)를 꺼냄
  @Autowired
  private JwtVerifier jwtVerifier;

  @Autowired
  private MemberRepository memberRepository;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private StringRedisTemplate redisTemplate;

  // 같은 초를 만들 때만 발급 시각을 고정하고, 그 전(폐기)에는 실제 DB 시계
  @MockitoSpyBean
  private DatabaseClock databaseClock;

  private final List<Long> memberIds = new ArrayList<>();
  private final List<Thread> issueThreads = new ArrayList<>();
  // 고정 시각을 한 번 받은(첫 시도에서 발급 시각 확인까지 간) 발급 스레드
  private final Set<String> firstClockReadDone = ConcurrentHashMap.newKeySet();

  @AfterEach
  void cleanUp() throws InterruptedException {
    // 발급 스레드가 끝난 뒤에 데이터를 지운다 (다음 테스트로 넘어가지 않게)
    for (Thread thread : issueThreads) {
      thread.join(WAIT.toMillis());
    }
    issueThreads.clear();
    firstClockReadDone.clear();
    for (Long id : memberIds) {
      jdbcTemplate.update("delete from member_token_revocation where member_id = ?", id);
      jdbcTemplate.update("delete from member where id = ?", id);
    }
    memberIds.clear();
    try (var connection = redisTemplate.getRequiredConnectionFactory().getConnection()) {
      connection.serverCommands().flushAll();
    }
  }

  // ── 같은 초에 발급 ──

  @Test
  @DisplayName("[S-A08 · #546] 폐기와 같은 초에 로컬 로그인하면 다음 초까지 기다린 뒤 발급해, 새 Access의 iat가 끊은 시각보다 뒤다 (Gateway 규칙상 거절 대상 아님)")
  void sameSecondLoginIssuesAfterCutoff() {
    Member member = saveMember();
    long cutoff = revoke(member.getId());
    fixClockToSameSecondThenNext(cutoff);

    LoginResult result = loginService.login(new LoginCommand(member.getEmail(), PASSWORD));

    assertThat(issuedAtOf(result.accessToken())).isGreaterThan(cutoff);
  }

  @Test
  @DisplayName("[S-A08 · #546] 폐기와 같은 초에 로그인 → 재발급: 로그인이 다음 초에 발급되므로 재발급 Access의 iat도 끊은 시각보다 뒤다")
  void sameSecondLoginThenReissueIssuesAfterCutoff() {
    Member member = saveMember();
    long cutoff = revoke(member.getId());
    fixClockToSameSecondThenNext(cutoff);
    String refreshToken = loginService.login(new LoginCommand(member.getEmail(), PASSWORD)).refreshToken();

    TokenReissueResult result = tokenReissueService.reissue(refreshToken);

    assertThat(issuedAtOf(result.accessToken())).isGreaterThan(cutoff);
  }

  @Test
  @DisplayName("[S-A08 · #546] 폐기 뒤 인증 버전의 Refresh로 같은 초에 재발급하면 다음 초까지 기다린 뒤 발급해, 새 Access의 iat가 끊은 시각보다 뒤다 (로그인 경로와 무관)")
  void sameSecondReissueIssuesAfterCutoff() {
    Member member = saveMember();
    long cutoff = revoke(member.getId());
    saveRefreshAfterRevocation(member.getId());
    fixClockToSameSecondThenNext(cutoff);

    TokenReissueResult result = tokenReissueService.reissue(REFRESH_TOKEN);

    assertThat(issuedAtOf(result.accessToken())).isGreaterThan(cutoff);
  }

  @Test
  @DisplayName("[S-A08 · #546] 재발급이 발급 시각을 기다리다 MEMBER_BUSY로 끝나도 Refresh는 소비되지 않아, 다음 요청으로 재발급할 수 있다")
  void reissueKeepsRefreshUntilIssuable() {
    Member member = saveMember();
    long cutoff = revoke(member.getId());
    saveRefreshAfterRevocation(member.getId());
    // 시각이 계속 같은 초 → 두 번 시도한 뒤 MEMBER_BUSY
    fixClockToSameSecond(cutoff);

    assertThatThrownBy(() -> tokenReissueService.reissue(REFRESH_TOKEN))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(MemberErrorCode.MEMBER_BUSY);
    assertThat(refreshTokenRedisStore.findOwner(REFRESH_TOKEN)).isPresent();

    // 다음 초가 되면 같은 Refresh로 재발급되고, 그때 소비된다
    doReturn(Instant.ofEpochSecond(cutoff + 1)).when(databaseClock).now();
    TokenReissueResult result = tokenReissueService.reissue(REFRESH_TOKEN);

    assertThat(issuedAtOf(result.accessToken())).isGreaterThan(cutoff);
    assertThat(refreshTokenRedisStore.findOwner(REFRESH_TOKEN)).isEmpty();
  }

  // ── 대기 중 추가 폐기 ──

  @Test
  @DisplayName("[S-A08 · #546] 로그인이 발급 시각을 기다리는 동안 다시 폐기되면, 새 폐기 시각을 다시 확인해 그보다 뒤인 초에 발급한다")
  void loginWaiting_revokedAgain_issuesAfterLatestCutoff() throws Exception {
    Member member = saveMember();
    revoke(member.getId());
    fixIssueThreadClock(member.getId());

    Issue<LoginResult> login = startIssue("issue-login",
        () -> loginService.login(new LoginCommand(member.getEmail(), PASSWORD)));
    awaitWaiting(login);
    long latestCutoff = revoke(member.getId());

    assertThat(issuedAtOf(resultOf(login).accessToken())).isGreaterThan(latestCutoff);
  }

  @Test
  @DisplayName("[S-A08 · #546] 재발급이 발급 시각을 기다리는 동안 다시 폐기되면, 인증 버전을 다시 확인해 INVALID_REFRESH_TOKEN")
  void reissueWaiting_revokedAgain_rejected() throws Exception {
    Member member = saveMember();
    revoke(member.getId());
    saveRefreshAfterRevocation(member.getId());
    fixIssueThreadClock(member.getId());

    Issue<TokenReissueResult> reissue = startIssue("issue-reissue",
        () -> tokenReissueService.reissue(REFRESH_TOKEN));
    awaitWaiting(reissue);
    revoke(member.getId());

    assertRejected(reissue, MemberErrorCode.INVALID_REFRESH_TOKEN);
  }

  // ── 대기 중 탈퇴 ──

  @Test
  @DisplayName("[S-A08 · #546] 로그인이 발급 시각을 기다리는 동안 탈퇴하면, 회원 상태를 다시 확인해 LOGIN_FAILED")
  void loginWaiting_withdrawn_rejected() throws Exception {
    Member member = saveMember();
    revoke(member.getId());
    fixIssueThreadClock(member.getId());

    Issue<LoginResult> login = startIssue("issue-login",
        () -> loginService.login(new LoginCommand(member.getEmail(), PASSWORD)));
    awaitWaiting(login);
    withdraw(member.getId());

    assertRejected(login, MemberErrorCode.LOGIN_FAILED);
  }

  @Test
  @DisplayName("[S-A08 · #546] 재발급이 발급 시각을 기다리는 동안 탈퇴하면, 회원 상태를 다시 확인해 INVALID_REFRESH_TOKEN")
  void reissueWaiting_withdrawn_rejected() throws Exception {
    Member member = saveMember();
    revoke(member.getId());
    saveRefreshAfterRevocation(member.getId());
    fixIssueThreadClock(member.getId());

    Issue<TokenReissueResult> reissue = startIssue("issue-reissue",
        () -> tokenReissueService.reissue(REFRESH_TOKEN));
    awaitWaiting(reissue);
    withdraw(member.getId());

    assertRejected(reissue, MemberErrorCode.INVALID_REFRESH_TOKEN);
  }

  @Test
  @DisplayName("[S-A08 · #546] 소셜 로그인이 발급 시각을 기다리는 동안 탈퇴하면, 회원 상태를 다시 확인해 SOCIAL_LOGIN_FAILED")
  void socialLoginWaiting_withdrawn_rejected() throws Exception {
    Member member = saveSocialMember();
    revoke(member.getId());
    fixIssueThreadClock(member.getId());

    Issue<SocialLoginResult> login = startIssue("issue-social-login",
        () -> socialLoginService.login(new SocialLoginCommand(Provider.KAKAO, member.getProviderId(), member.getEmail())));
    awaitWaiting(login);
    withdraw(member.getId());

    assertRejected(login, MemberErrorCode.SOCIAL_LOGIN_FAILED);
  }

  // ── 같은 Refresh 동시 재발급 ──

  @Test
  @DisplayName("[S-A08 · #546] 같은 Refresh로 두 요청이 모두 발급 시각을 기다린 뒤 다시 실행해도 하나만 성공한다")
  void sameRefresh_bothWaiting_onlyOneSucceeds() throws Exception {
    Member member = saveMember();
    long cutoff = revoke(member.getId());
    saveRefreshAfterRevocation(member.getId());
    fixIssueThreadClock(member.getId());

    Issue<TokenReissueResult> first = startIssue("issue-reissue-1", () -> tokenReissueService.reissue(REFRESH_TOKEN));
    Issue<TokenReissueResult> second = startIssue("issue-reissue-2", () -> tokenReissueService.reissue(REFRESH_TOKEN));
    // 두 요청 모두 Refresh를 꺼내지 않은 채 대기에 들어감
    awaitWaiting(first);
    awaitWaiting(second);

    int succeeded = 0;
    int rejected = 0;
    for (Issue<TokenReissueResult> issue : List.of(first, second)) {
      try {
        assertThat(issuedAtOf(resultOf(issue).accessToken())).isGreaterThan(cutoff);
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

  // ── 폐기 · 탈퇴 ──

  // 실제 폐기 처리로 Redis에 끊은 시각(초)을 남기고, Gateway TokenRevocationChecker와 같은 방식으로 읽음
  // (사유는 결과에 영향이 없어 하나로 둔다. 회원 정보는 바꾸지 않고 인증 버전만 올라간다)
  private long revoke(Long memberId) {
    revocationService.revoke(memberId, RevocationReason.PASSWORD_CHANGED, member -> { });
    return Long.parseLong(redisTemplate.opsForValue().get("auth:revoked-member:" + memberId));
  }

  // 탈퇴 흉내: 실제 폐기 처리 안에서 회원 상태만 DELETED로 (TokenIssueRevocationRaceIntegrationTest와 같은 방식)
  private void withdraw(Long memberId) {
    revocationService.revoke(memberId, RevocationReason.WITHDRAWN,
        member -> ReflectionTestUtils.setField(member, "status", MemberStatus.DELETED));
  }

  // 폐기가 올린 인증 버전(1)으로 저장한 Refresh: 로그인 경로를 거치지 않고 재발급만 확인
  private void saveRefreshAfterRevocation(Long memberId) {
    refreshTokenRedisStore.save(REFRESH_TOKEN, memberId, 1, Duration.ofDays(1));
  }

  // ── 시각 고정 ──

  // 시각에 따라 들쭉날쭉하지 않도록, 이후 발급 시각을 끊은 시각과 같은 초(0.5초 뒤)로 고정
  private void fixClockToSameSecond(long cutoff) {
    doReturn(Instant.ofEpochSecond(cutoff).plusMillis(500)).when(databaseClock).now();
  }

  // 첫 시각은 끊은 시각과 같은 초, 그 뒤로는 다음 초 (실제 시각에 따라 결과가 달라지지 않게 둘 다 고정)
  private void fixClockToSameSecondThenNext(long cutoff) {
    doReturn(Instant.ofEpochSecond(cutoff).plusMillis(500), Instant.ofEpochSecond(cutoff + 1))
        .when(databaseClock).now();
  }

  // 발급 스레드만 시각을 고정하고, 다른 스레드(대기 중에 끼어드는 폐기 · 탈퇴)는 실제 DB 시계를 쓴다
  // - 스레드마다 첫 시각: 가장 늦은 폐기 시각과 같은 초의 시작 → 다음 초까지 1초를 기다리게 된다
  // - 그 뒤: 그 시점의 가장 늦은 폐기 시각의 다음 초 (대기 중에 추가된 폐기 포함. 실제 시계에 맡기면 추가 폐기와 같은 초에 깰 수 있다)
  private void fixIssueThreadClock(Long memberId) {
    doAnswer(invocation -> {
      String threadName = Thread.currentThread().getName();
      if (!threadName.startsWith(ISSUE_THREAD_PREFIX)) {
        return invocation.callRealMethod();
      }
      long latestCutoff = revocationRepository.findLatestCutoffAt(memberId).orElseThrow().getEpochSecond();
      boolean firstRead = firstClockReadDone.add(threadName);
      return Instant.ofEpochSecond(firstRead ? latestCutoff : latestCutoff + 1);
    }).when(databaseClock).now();
  }

  // ── 발급 스레드 ──

  // 별도 스레드에서 실행한 발급 요청
  private record Issue<T>(Thread thread, FutureTask<T> result) {
  }

  private <T> Issue<T> startIssue(String threadName, Callable<T> task) {
    FutureTask<T> result = new FutureTask<>(task);
    Thread thread = new Thread(result, threadName);
    issueThreads.add(thread);
    thread.start();
    return new Issue<>(thread, result);
  }

  // 발급 요청이 첫 시도에서 발급 시각 확인까지 간 뒤(롤백), 트랜잭션 밖에서 기다리는 상태가 될 때까지 확인
  private void awaitWaiting(Issue<?> issue) throws InterruptedException {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (System.nanoTime() < deadline) {
      if (firstClockReadDone.contains(issue.thread().getName())
          && issue.thread().getState() == Thread.State.TIMED_WAITING) {
        return;
      }
      Thread.sleep(10);
    }
    fail("발급 요청이 대기 상태에 들어가지 않았습니다: %s", issue.thread().getName());
  }

  private static <T> T resultOf(Issue<T> issue) throws Exception {
    return issue.result().get(WAIT.toSeconds(), TimeUnit.SECONDS);
  }

  private static void assertRejected(Issue<?> issue, MemberErrorCode errorCode) {
    assertThatThrownBy(() -> resultOf(issue))
        .isInstanceOf(ExecutionException.class)
        .satisfies(e -> assertThat(e.getCause())
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(errorCode));
  }

  // ── 준비 도구 ──

  private long issuedAtOf(String accessToken) {
    return jwtVerifier.verifyAccessToken(accessToken).issuedAt();
  }

  private Member saveMember() {
    int n = SEQ.incrementAndGet();
    Member member = memberRepository.save(Member.createLocal(
        "samesecond" + n + "@test.com", passwordEncoder.encode(PASSWORD), "같은초" + n, String.format("014%08d", n),
        "004", "v1:test-account", null, "홍길동"));
    memberIds.add(member.getId());
    return member;
  }

  private Member saveSocialMember() {
    int n = SEQ.incrementAndGet();
    Member member = memberRepository.save(Member.createSocial(
        Provider.KAKAO, "samesecond-kakao-" + n, "samesecond" + n + "@kakao.com", "같은초" + n, String.format("014%08d", n),
        "004", "v1:test-account", null, "홍길동"));
    memberIds.add(member.getId());
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

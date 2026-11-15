package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.member.auth.domain.RevocationReason;
import com.justcommit.backend.member.auth.domain.RevocationStatus;
import com.justcommit.backend.member.auth.domain.TokenRevocation;
import com.justcommit.backend.member.auth.infrastructure.redis.RevokedMemberRedisStore;
import com.justcommit.backend.member.auth.infrastructure.repository.TokenRevocationRepository;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * 폐기 작업 재처리 (#409)를 실제 PostgreSQL · Redis로 확인한다.
 * 스케줄러는 테스트 설정에서 꺼 두고 재처리 서비스를 직접 부른다.
 * "임대가 끝남"은 30초를 기다리지 않고 next_attempt_at을 1초 전으로 돌려서 만든다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class TokenRevocationRetryIntegrationTest {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final Duration RETENTION = Duration.ofMinutes(31);
  // 시각 비교 여유: 앱 · DB 호출 사이 지연을 감안
  private static final Duration SLACK = Duration.ofSeconds(3);
  private static final AtomicInteger SEQ = new AtomicInteger();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @Autowired
  private TokenRevocationRetryService retryService;

  @Autowired
  private TokenRevocationRecorder revocationRecorder;

  @Autowired
  private TokenRevocationRepository revocationRepository;

  @Autowired
  private MemberRepository memberRepository;

  @Autowired
  private DatabaseClock databaseClock;

  @Autowired
  private StringRedisTemplate redisTemplate;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @MockitoSpyBean
  private RevokedMemberRedisStore revokedMemberRedisStore;

  @BeforeEach
  void cleanUp() {
    revocationRepository.deleteAll();
    try (var connection = redisTemplate.getRequiredConnectionFactory().getConnection()) {
      connection.serverCommands().flushAll();
    }
  }

  @Test
  @DisplayName("재처리는 다음 시도 시각이 지난 PENDING 작업을 기록하고 RECORDED로 끝낸다")
  void retryRecordsPending() {
    Member member = saveMember();
    TokenRevocation pending = saveDueRevocation(member, databaseClock.now().minusSeconds(60));

    assertThat(retryService.retryPending()).isEqualTo(1);

    assertThat(reload(pending).getStatus()).isEqualTo(RevocationStatus.RECORDED);
    assertThat(redisTemplate.opsForValue().get(revokedKey(member.getId())))
        .isEqualTo(String.valueOf(pending.getCutoffAt().getEpochSecond()));
  }

  @Test
  @DisplayName("보관 기한이 지난 PENDING 작업은 기록하지 않고 EXPIRED로 끝낸다")
  void retryExpiresOldRevocation() {
    Member member = saveMember();
    TokenRevocation old = saveDueRevocation(member, databaseClock.now().minus(RETENTION).minusSeconds(5));

    assertThat(retryService.retryPending()).isEqualTo(1);

    assertThat(reload(old).getStatus()).isEqualTo(RevocationStatus.EXPIRED);
    assertThat(redisTemplate.hasKey(revokedKey(member.getId()))).isFalse();
  }

  @Test
  @DisplayName("임대 직후에는 다른 실행이 같은 작업을 잡지 못한다")
  void leasedRevocationIsNotClaimedAgain() {
    TokenRevocation due = saveDueRevocation(saveMember(), databaseClock.now().minusSeconds(60));

    Instant before = databaseClock.now();
    Optional<TokenRevocation> first = retryService.claimNext();
    Optional<TokenRevocation> second = retryService.claimNext();

    assertThat(first).isPresent();
    assertThat(first.get().getId()).isEqualTo(due.getId());
    assertThat(first.get().getNextAttemptAt())
        .isBetween(before.plus(TokenRevocationRetryService.LEASE), before.plus(TokenRevocationRetryService.LEASE).plus(SLACK));
    assertThat(reload(due).getNextAttemptAt()).isEqualTo(first.get().getNextAttemptAt());
    assertThat(second).isEmpty();
  }

  @Test
  @DisplayName("임대가 끝나면 같은 작업이 새 임대로 다시 잡힌다")
  void expiredLeaseIsClaimedAgain() {
    TokenRevocation due = saveDueRevocation(saveMember(), databaseClock.now().minusSeconds(60));
    TokenRevocation first = retryService.claimNext().orElseThrow();

    expireLease(due.getId());
    Optional<TokenRevocation> again = retryService.claimNext();

    assertThat(again).isPresent();
    assertThat(again.get().getId()).isEqualTo(due.getId());
    assertThat(again.get().getNextAttemptAt()).isNotEqualTo(first.getNextAttemptAt());
  }

  @Test
  @DisplayName("이전 실행의 늦은 실패는 새 임대를 덮어쓰지 않고, 새 실행의 실패만 기록된다")
  void lateFailureOfPreviousRunDoesNotTouchNewLease() {
    TokenRevocation due = saveDueRevocation(saveMember(), databaseClock.now().minusSeconds(60));
    TokenRevocation previousRun = retryService.claimNext().orElseThrow();
    expireLease(due.getId());
    TokenRevocation currentRun = retryService.claimNext().orElseThrow();
    doThrow(new RedisConnectionFailureException("redis down"))
        .when(revokedMemberRedisStore).recordCutoff(anyLong(), any(), any());

    // 이전 실행이 늦게 실패: 들고 있는 임대 값이 DB와 달라 0행
    assertThat(revocationRecorder.record(previousRun)).isFalse();
    TokenRevocation afterPrevious = reload(due);
    assertThat(afterPrevious.getStatus()).isEqualTo(RevocationStatus.PENDING);
    assertThat(afterPrevious.getAttemptCount()).isZero();
    assertThat(afterPrevious.getLastError()).isNull();
    assertThat(afterPrevious.getNextAttemptAt()).isEqualTo(currentRun.getNextAttemptAt());

    // 새 실행의 실패는 기록됨
    assertThat(revocationRecorder.record(currentRun)).isFalse();
    TokenRevocation afterCurrent = reload(due);
    assertThat(afterCurrent.getAttemptCount()).isEqualTo(1);
    assertThat(afterCurrent.getLastError()).contains("redis down");
  }

  @Test
  @DisplayName("실패할 때마다 다음 시도는 실패 시점부터 +10초 → +30초 → +1분")
  void retryDelayGrowsWithAttempts() {
    TokenRevocation due = saveDueRevocation(saveMember(), databaseClock.now().minusSeconds(60));
    doThrow(new RedisConnectionFailureException("redis down"))
        .when(revokedMemberRedisStore).recordCutoff(anyLong(), any(), any());

    assertNextDelay(due, Duration.ofSeconds(10));
    assertNextDelay(due, Duration.ofSeconds(30));
    assertNextDelay(due, Duration.ofMinutes(1));
    assertNextDelay(due, Duration.ofMinutes(1));
    assertThat(reload(due).getAttemptCount()).isEqualTo(4);
  }

  // 지금 DB에 있는 임대 값으로 실패 한 번 → 다음 시도가 실패 시점 + delay인지
  private void assertNextDelay(TokenRevocation due, Duration delay) {
    TokenRevocation current = reload(due);
    Instant before = databaseClock.now();
    assertThat(revocationRecorder.record(current)).isFalse();
    Instant after = databaseClock.now();
    assertThat(reload(due).getNextAttemptAt()).isBetween(before.plus(delay), after.plus(delay));
  }

  // 다음 시도 시각이 이미 지난 PENDING 작업 (now 기준으로 만든 작업의 최초 임대 30초가 끝난 상태)
  private TokenRevocation saveDueRevocation(Member member, Instant now) {
    return revocationRepository.save(
        TokenRevocation.create(member.getId(), RevocationReason.PASSWORD_CHANGED, 1, now, RETENTION));
  }

  private void expireLease(Long revocationId) {
    jdbcTemplate.update("UPDATE member_token_revocation SET next_attempt_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
        revocationId);
  }

  private TokenRevocation reload(TokenRevocation revocation) {
    return revocationRepository.findById(revocation.getId()).orElseThrow();
  }

  private Member saveMember() {
    int n = SEQ.incrementAndGet();
    return memberRepository.save(Member.createLocal(
        "retry" + n + "@test.com", passwordEncoder.encode("plant1234"), "재처리" + n, String.format("014%08d", n),
        "004", "v1:test-account", "홍길동"));
  }

  private static String revokedKey(Long memberId) {
    return "auth:revoked-member:" + memberId;
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

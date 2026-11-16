package com.justcommit.backend.member.auth.infrastructure.redis;

import com.justcommit.backend.member.auth.domain.EmailPurpose;
import com.justcommit.backend.member.auth.infrastructure.redis.EmailVerificationRedisStore.SendResult;
import com.justcommit.backend.member.auth.infrastructure.redis.EmailVerificationRedisStore.SendStatus;
import com.justcommit.backend.member.auth.infrastructure.redis.EmailVerificationRedisStore.VerifyResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이메일 인증 저장소를 실제 Redis로 확인한다 (#412, Lua 스크립트 동작은 가짜 Redis로 확인할 수 없음).
 * 스프링 컨텍스트 없이 Redis 연결만 직접 만든다. Docker가 없으면 건너뜀.
 */
@Testcontainers(disabledWithoutDocker = true)
class EmailVerificationRedisStoreTest {

  private static final String EMAIL = "test@example.com";
  private static final Duration COOLDOWN = Duration.ofSeconds(60);
  private static final int DAILY_LIMIT = 5;
  private static final Duration DAILY_WINDOW = Duration.ofDays(1);
  private static final int MAX_WRONG = 5;
  private static final Duration CODE_TTL = Duration.ofMinutes(5);

  private static final String COOLDOWN_KEY = "auth:email:cooldown:" + EMAIL;
  private static final String DAILY_KEY = "auth:email:daily:" + EMAIL;
  private static final String CODE_KEY = "auth:email:signup:" + EMAIL;
  private static final String ATTEMPTS_KEY = "auth:email:attempts:signup:" + EMAIL;

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate redisTemplate;

  private EmailVerificationRedisStore store;

  @BeforeAll
  static void connect() {
    connectionFactory = new LettuceConnectionFactory(
        new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
    connectionFactory.afterPropertiesSet();
    connectionFactory.start();
    redisTemplate = new StringRedisTemplate(connectionFactory);
  }

  @AfterAll
  static void disconnect() {
    connectionFactory.destroy();
  }

  @BeforeEach
  void setUp() {
    try (var connection = connectionFactory.getConnection()) {
      connection.serverCommands().flushAll();
    }
    store = new EmailVerificationRedisStore(redisTemplate);
  }

  private SendResult acquire() {
    return store.tryAcquireSend(EMAIL, COOLDOWN, DAILY_LIMIT, DAILY_WINDOW);
  }

  // 하루 발송 Set의 원소 수 (키가 없으면 0)
  private long dailyCount() {
    Long size = redisTemplate.opsForSet().size(DAILY_KEY);
    return size == null ? 0 : size;
  }

  // 발송 자리를 잡은 요청으로 인증번호를 저장한다 (저장은 재발송 제한의 주인만 가능)
  private void saveOwnCode(EmailPurpose purpose, String code, Duration ttl) {
    redisTemplate.delete(COOLDOWN_KEY);
    SendResult sent = acquire();
    assertThat(store.saveCode(purpose, EMAIL, sent.requestId(), code, ttl)).isTrue();
  }

  private VerifyResult verify(EmailPurpose purpose, String code) {
    return store.verifyCode(purpose, EMAIL, code, MAX_WRONG, CODE_TTL);
  }

  // ===== 발송 자리 =====

  @Test
  @DisplayName("첫 발송은 자리를 확보하고, 재발송 제한(60초) · 하루 횟수(1, 첫 발송부터 1일)를 만든다")
  void acquire_first() {
    assertThat(acquire().status()).isEqualTo(SendStatus.ACQUIRED);

    assertThat(redisTemplate.getExpire(COOLDOWN_KEY)).isBetween(1L, 60L);
    assertThat(dailyCount()).isEqualTo(1L);
    assertThat(redisTemplate.getExpire(DAILY_KEY)).isBetween(86_000L, 86_400L);
  }

  @Test
  @DisplayName("재발송 제한 중이면 COOLDOWN과 남은 초, 하루 횟수는 늘지 않는다")
  void acquire_cooldown() {
    acquire();

    SendResult second = acquire();

    assertThat(second.status()).isEqualTo(SendStatus.COOLDOWN);
    assertThat(second.retryAfterSeconds()).isBetween(1L, 60L);
    assertThat(dailyCount()).isEqualTo(1L);
  }

  @Test
  @DisplayName("같은 이메일은 하루 5번까지 확보하고, 6번째는 DAILY_LIMIT · 재발송 제한도 시작하지 않는다")
  void acquire_dailyLimit() {
    for (int i = 0; i < DAILY_LIMIT; i++) {
      assertThat(acquire().status()).isEqualTo(SendStatus.ACQUIRED);
      redisTemplate.delete(COOLDOWN_KEY); // 60초를 기다리는 대신 재발송 제한만 지움
    }

    SendResult sixth = acquire();

    assertThat(sixth.status()).isEqualTo(SendStatus.DAILY_LIMIT);
    assertThat(sixth.retryAfterSeconds()).isGreaterThan(60L);
    assertThat(dailyCount()).isEqualTo(5L);
    assertThat(redisTemplate.hasKey(COOLDOWN_KEY)).isFalse();
  }

  @Test
  @DisplayName("같은 이메일로 동시에 발송을 요청해도 한 번만 확보한다")
  void acquire_concurrent() throws Exception {
    List<SendResult> results = runTogether(10, this::acquire);

    assertThat(results).filteredOn(r -> r.status() == SendStatus.ACQUIRED).hasSize(1);
    assertThat(dailyCount()).isEqualTo(1L);
  }

  @Test
  @DisplayName("발송 실패로 되돌리면 그 요청의 인증번호 · 재발송 제한이 지워지고 하루 횟수가 1 줄어 바로 다시 보낼 수 있다")
  void release() {
    SendResult sent = acquire();
    store.saveCode(EmailPurpose.SIGNUP, EMAIL, sent.requestId(), "123456", CODE_TTL);

    store.releaseSend(EmailPurpose.SIGNUP, EMAIL, sent.requestId());

    assertThat(redisTemplate.hasKey(CODE_KEY)).isFalse();
    assertThat(redisTemplate.hasKey(COOLDOWN_KEY)).isFalse();
    assertThat(dailyCount()).isZero();
    assertThat(acquire().status()).isEqualTo(SendStatus.ACQUIRED);
  }

  @Test
  @DisplayName("앞 요청이 늦게 실패해도 뒤 요청의 인증번호 · 재발송 제한은 그대로이고, 하루 횟수만 1 줄어든다")
  void release_doesNotTouchLaterRequest() {
    SendResult first = acquire();
    store.saveCode(EmailPurpose.SIGNUP, EMAIL, first.requestId(), "111111", CODE_TTL);
    redisTemplate.delete(COOLDOWN_KEY); // 앞 요청의 메일 발송이 60초 넘게 걸린 상황
    SendResult second = acquire();
    store.saveCode(EmailPurpose.SIGNUP, EMAIL, second.requestId(), "222222", CODE_TTL);

    store.releaseSend(EmailPurpose.SIGNUP, EMAIL, first.requestId());

    assertThat(redisTemplate.opsForValue().get(COOLDOWN_KEY)).isEqualTo(second.requestId());
    assertThat(dailyCount()).isEqualTo(1L);
    assertThat(verify(EmailPurpose.SIGNUP, "222222")).isEqualTo(VerifyResult.MATCHED);
  }

  @Test
  @DisplayName("하루 구간이 바뀐 뒤 앞 요청이 실패해도 새 구간의 횟수 · 재발송 제한은 그대로다")
  void release_afterWindowRollover() {
    SendResult first = acquire();
    redisTemplate.delete(List.of(DAILY_KEY, COOLDOWN_KEY)); // 앞 요청 발송 중에 하루 구간이 끝난 상황
    SendResult second = acquire();

    store.releaseSend(EmailPurpose.SIGNUP, EMAIL, first.requestId());

    assertThat(dailyCount()).isEqualTo(1L);
    assertThat(redisTemplate.opsForValue().get(COOLDOWN_KEY)).isEqualTo(second.requestId());
  }

  @Test
  @DisplayName("같은 요청을 두 번 되돌려도 하루 횟수는 한 번만 줄어든다")
  void release_twice() {
    SendResult first = acquire();
    redisTemplate.delete(COOLDOWN_KEY);
    acquire();

    store.releaseSend(EmailPurpose.SIGNUP, EMAIL, first.requestId());
    store.releaseSend(EmailPurpose.SIGNUP, EMAIL, first.requestId());

    assertThat(dailyCount()).isEqualTo(1L);
  }

  @Test
  @DisplayName("자리를 잡은 뒤 멈춘 사이 다른 요청이 새 번호를 저장했다면, 늦게 재개된 요청은 그 번호를 덮어쓰지 못한다")
  void saveCode_onlyByCooldownOwner() {
    SendResult first = acquire();
    redisTemplate.delete(COOLDOWN_KEY); // 앞 요청이 저장 전에 60초 넘게 멈춘 상황
    SendResult second = acquire();
    store.saveCode(EmailPurpose.SIGNUP, EMAIL, second.requestId(), "222222", CODE_TTL);

    boolean saved = store.saveCode(EmailPurpose.SIGNUP, EMAIL, first.requestId(), "111111", CODE_TTL);

    assertThat(saved).isFalse();
    assertThat(verify(EmailPurpose.SIGNUP, "222222")).isEqualTo(VerifyResult.MATCHED);
  }

  @Test
  @DisplayName("재발송 제한이 1초 미만 남아도 키가 있는 동안은 COOLDOWN, 남은 시간은 1초로 응답")
  void acquire_cooldownBelowOneSecond() {
    redisTemplate.opsForValue().set(COOLDOWN_KEY, "other-request", Duration.ofMillis(500));

    SendResult result = acquire();

    assertThat(result.status()).isEqualTo(SendStatus.COOLDOWN);
    assertThat(result.retryAfterSeconds()).isEqualTo(1L);
    assertThat(redisTemplate.hasKey(DAILY_KEY)).isFalse();
  }

  // ===== 인증번호 확인 =====

  @Test
  @DisplayName("일치하면 MATCHED, 인증번호와 틀린 횟수를 모두 지운다")
  void verify_match() {
    saveOwnCode(EmailPurpose.SIGNUP, "123456", CODE_TTL);
    verify(EmailPurpose.SIGNUP, "000000");

    assertThat(verify(EmailPurpose.SIGNUP, "123456")).isEqualTo(VerifyResult.MATCHED);
    assertThat(redisTemplate.hasKey(CODE_KEY)).isFalse();
    assertThat(redisTemplate.hasKey(ATTEMPTS_KEY)).isFalse();
  }

  @Test
  @DisplayName("4번까지는 MISMATCH, 5번째에 EXHAUSTED로 폐기되고 그 뒤에는 맞는 번호도 EXPIRED")
  void verify_exhausted() {
    saveOwnCode(EmailPurpose.SIGNUP, "123456", CODE_TTL);

    for (int i = 0; i < MAX_WRONG - 1; i++) {
      assertThat(verify(EmailPurpose.SIGNUP, "000000")).isEqualTo(VerifyResult.MISMATCH);
    }
    assertThat(redisTemplate.getExpire(ATTEMPTS_KEY)).isBetween(1L, 300L);

    assertThat(verify(EmailPurpose.SIGNUP, "000000")).isEqualTo(VerifyResult.EXHAUSTED);
    assertThat(redisTemplate.hasKey(CODE_KEY)).isFalse();
    assertThat(verify(EmailPurpose.SIGNUP, "123456")).isEqualTo(VerifyResult.EXPIRED);
  }

  @Test
  @DisplayName("새 인증번호를 저장하면 틀린 횟수가 다시 시작된다")
  void saveCode_resetsAttempts() {
    saveOwnCode(EmailPurpose.SIGNUP, "123456", CODE_TTL);
    for (int i = 0; i < MAX_WRONG - 1; i++) {
      verify(EmailPurpose.SIGNUP, "000000");
    }

    saveOwnCode(EmailPurpose.SIGNUP, "654321", CODE_TTL);

    for (int i = 0; i < MAX_WRONG - 1; i++) {
      assertThat(verify(EmailPurpose.SIGNUP, "000000")).isEqualTo(VerifyResult.MISMATCH);
    }
    assertThat(verify(EmailPurpose.SIGNUP, "654321")).isEqualTo(VerifyResult.MATCHED);
  }

  @Test
  @DisplayName("같은 번호로 동시에 확인해도 한 번만 MATCHED (나머지는 이미 지워져 EXPIRED)")
  void verify_concurrent() throws Exception {
    saveOwnCode(EmailPurpose.SIGNUP, "123456", CODE_TTL);

    List<VerifyResult> results = runTogether(10, () -> verify(EmailPurpose.SIGNUP, "123456"));

    assertThat(results).filteredOn(r -> r == VerifyResult.MATCHED).hasSize(1);
    assertThat(results).filteredOn(r -> r == VerifyResult.EXPIRED).hasSize(9);
  }

  // ===== 목적 구분 =====

  @Test
  @DisplayName("가입 목적의 인증번호 · 인증 완료는 비밀번호 재설정 목적으로 쓸 수 없다")
  void purposesAreSeparated() {
    saveOwnCode(EmailPurpose.SIGNUP, "123456", CODE_TTL);
    assertThat(verify(EmailPurpose.PASSWORD_RESET, "123456")).isEqualTo(VerifyResult.EXPIRED);

    store.saveVerified(EmailPurpose.SIGNUP, EMAIL, Duration.ofMinutes(30));
    assertThat(store.isVerified(EmailPurpose.SIGNUP, EMAIL)).isTrue();
    assertThat(store.isVerified(EmailPurpose.PASSWORD_RESET, EMAIL)).isFalse();
  }

  // 같은 작업을 여러 스레드에서 같은 순간에 시작시키고 결과를 모은다
  private <T> List<T> runTogether(int threads, Callable<T> task) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<T>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(executor.submit(() -> {
          start.await();
          return task.call();
        }));
      }
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get(10, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      executor.shutdownNow();
    }
  }
}

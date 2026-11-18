package com.justcommit.backend.member.auth.infrastructure.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.justcommit.backend.common.testcontainers.SharedRedis;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 현재 비밀번호 실패 횟수 저장소를 실제 Redis로 확인한다 (#412, Lua · KEEPTTL 동작).
 * 스프링 컨텍스트 없이 Redis 연결만 직접 만든다. Docker가 없으면 건너뜀.
 */
@Testcontainers(disabledWithoutDocker = true)
class PasswordFailureStoreTest {

  @DynamicPropertySource
  static void containers(DynamicPropertyRegistry registry) {
    SharedRedis.register(registry);
  }

  private static final Long MEMBER_ID = 7L;
  private static final String KEY = "auth:password:failures:7";
  private static final int LIMIT = 5;
  private static final Duration WINDOW = Duration.ofDays(1);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate redisTemplate;

  private PasswordFailureStore store;

  @BeforeAll
  static void connect() {
    connectionFactory = new LettuceConnectionFactory(
        new RedisStandaloneConfiguration(SharedRedis.container().getHost(), SharedRedis.container().getMappedPort(6379)));
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
    store = new PasswordFailureStore(redisTemplate);
  }

  private void fail(int tokenVersion, int times) {
    for (int i = 0; i < times; i++) {
      store.recordFailure(MEMBER_ID, tokenVersion, WINDOW);
    }
  }

  @Test
  @DisplayName("기록이 없거나 4번까지 틀렸으면 막히지 않고, 같은 버전에서 5번 틀리면 남은 시간과 함께 막힌다")
  void lockedAfterLimit() {
    assertThat(store.retryAfterSeconds(MEMBER_ID, 0, LIMIT)).isZero();

    fail(0, 4);
    assertThat(store.retryAfterSeconds(MEMBER_ID, 0, LIMIT)).isZero();

    assertThat(store.recordFailure(MEMBER_ID, 0, WINDOW)).isEqualTo(5);
    assertThat(store.retryAfterSeconds(MEMBER_ID, 0, LIMIT)).isBetween(86_000L, 86_400L);
    assertThat(redisTemplate.opsForValue().get(KEY)).isEqualTo("0:5");
  }

  @Test
  @DisplayName("저장된 버전이 지금 버전과 다르면 0회로 본다 (비밀번호가 바뀌면 옛 횟수는 적용되지 않음)")
  void otherVersionIsNotLocked() {
    fail(0, 5);

    assertThat(store.retryAfterSeconds(MEMBER_ID, 1, LIMIT)).isZero();
  }

  @Test
  @DisplayName("같은 버전의 오답은 횟수만 늘리고 만료 시각은 연장하지 않는다")
  void sameVersionKeepsTtl() {
    fail(0, 1);
    redisTemplate.expire(KEY, Duration.ofSeconds(100)); // 첫 오답 뒤 시간이 흘러 100초만 남은 상황

    fail(0, 1);

    assertThat(redisTemplate.opsForValue().get(KEY)).isEqualTo("0:2");
    assertThat(redisTemplate.getExpire(KEY)).isBetween(1L, 100L);
  }

  @Test
  @DisplayName("다른 버전의 첫 오답은 횟수 1부터 새 24시간을 시작한다")
  void newVersionStartsOver() {
    fail(0, 3);
    redisTemplate.expire(KEY, Duration.ofSeconds(100));

    assertThat(store.recordFailure(MEMBER_ID, 1, WINDOW)).isEqualTo(1);

    assertThat(redisTemplate.opsForValue().get(KEY)).isEqualTo("1:1");
    assertThat(redisTemplate.getExpire(KEY)).isBetween(86_000L, 86_400L);
  }

  @Test
  @DisplayName("초기화는 변경 전 버전의 횟수만 지우고, 새 버전에서 쌓인 기록은 남긴다")
  void clearOnlyOldVersion() {
    fail(0, 3);
    store.recordFailure(MEMBER_ID, 1, WINDOW); // 커밋 직후 새 버전에서 오답이 먼저 기록된 상황

    store.clear(MEMBER_ID, 0);
    assertThat(redisTemplate.opsForValue().get(KEY)).isEqualTo("1:1");

    store.clear(MEMBER_ID, 1);
    assertThat(redisTemplate.hasKey(KEY)).isFalse();
  }

  @Test
  @DisplayName("막혀 있고 1초 미만이 남았으면 1초로 응답한다 (밀리초 올림, 최소 1초)")
  void retryAfterRoundsUp() {
    redisTemplate.opsForValue().set(KEY, "0:5", Duration.ofMillis(500));

    assertThat(store.retryAfterSeconds(MEMBER_ID, 0, LIMIT)).isEqualTo(1L);
  }
}

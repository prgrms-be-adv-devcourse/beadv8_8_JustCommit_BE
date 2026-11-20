package com.justcommit.backend.member.auth.infrastructure.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.justcommit.backend.common.testcontainers.SharedRedis;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 폐기 시각 기록 Lua를 실제 Redis로 확인한다 (#409). 최초 기록과 재처리가 같은 Lua를 쓴다.
 * 폐기 시각은 뒤로 밀리지 않고, 같은 폐기 시각이면 더 긴 보관 기한을 유지한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class RevokedMemberRedisStoreTest {

  @DynamicPropertySource
  static void containers(DynamicPropertyRegistry registry) {
    SharedRedis.register(registry);
  }

  private static final String KEY = "auth:revoked-member:7";
  private static final Instant CUTOFF = Instant.parse("2026-11-15T00:00:00Z");
  private static final Duration RETENTION = Duration.ofMinutes(31);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate redisTemplate;

  private RevokedMemberRedisStore store;

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
    store = new RevokedMemberRedisStore(redisTemplate);
  }

  @Test
  @DisplayName("처음 기록하면 폐기 시각을 초 단위로, 보관 기한을 밀리초 TTL로 저장한다")
  void firstRecord() {
    assertThat(store.recordCutoff(7L, CUTOFF.plusMillis(900), RETENTION)).isTrue();

    assertThat(redisTemplate.opsForValue().get(KEY)).isEqualTo(String.valueOf(CUTOFF.getEpochSecond()));
    assertThat(pttl()).isBetween(RETENTION.minusSeconds(5).toMillis(), RETENTION.toMillis());
  }

  @Test
  @DisplayName("더 최신 폐기 시각이 있으면 늦게 온 옛 폐기 시각은 값과 만료를 바꾸지 않는다")
  void olderCutoffDoesNotOverwrite() {
    store.recordCutoff(7L, CUTOFF.plusSeconds(10), RETENTION);
    long before = pttl();

    assertThat(store.recordCutoff(7L, CUTOFF, Duration.ofHours(2))).isFalse();

    assertThat(redisTemplate.opsForValue().get(KEY)).isEqualTo(String.valueOf(CUTOFF.plusSeconds(10).getEpochSecond()));
    assertThat(pttl()).isLessThanOrEqualTo(before);
  }

  @Test
  @DisplayName("저장된 폐기 시각보다 새 폐기 시각이 늦으면 새 값으로 갱신한다")
  void newerCutoffOverwrites() {
    store.recordCutoff(7L, CUTOFF, RETENTION);

    assertThat(store.recordCutoff(7L, CUTOFF.plusSeconds(10), RETENTION)).isTrue();

    assertThat(redisTemplate.opsForValue().get(KEY)).isEqualTo(String.valueOf(CUTOFF.plusSeconds(10).getEpochSecond()));
  }

  @Test
  @DisplayName("같은 폐기 시각이면 더 긴 보관 기한으로 늘린다")
  void sameCutoffExtendsRetention() {
    store.recordCutoff(7L, CUTOFF, Duration.ofMinutes(10));

    assertThat(store.recordCutoff(7L, CUTOFF, RETENTION)).isFalse();

    assertThat(pttl()).isGreaterThan(Duration.ofMinutes(30).toMillis());
  }

  @Test
  @DisplayName("같은 폐기 시각의 더 짧은 보관 기한은 만료를 줄이지 않는다")
  void sameCutoffDoesNotShortenRetention() {
    store.recordCutoff(7L, CUTOFF, RETENTION);

    assertThat(store.recordCutoff(7L, CUTOFF, Duration.ofMinutes(1))).isFalse();

    assertThat(pttl()).isGreaterThan(Duration.ofMinutes(30).toMillis());
  }

  @Test
  @DisplayName("숫자가 아닌 값은 새 폐기 시각으로 덮어쓴다")
  void nonNumericValueIsOverwritten() {
    redisTemplate.opsForValue().set(KEY, "abc", Duration.ofMinutes(1));

    assertThat(store.recordCutoff(7L, CUTOFF, RETENTION)).isTrue();

    assertThat(redisTemplate.opsForValue().get(KEY)).isEqualTo(String.valueOf(CUTOFF.getEpochSecond()));
  }

  @Test
  @DisplayName("만료가 없는 키도 같은 폐기 시각으로 기록하면 만료가 걸린다 (TTL 없는 키를 남기지 않음)")
  void keyWithoutExpireGetsExpire() {
    redisTemplate.opsForValue().set(KEY, String.valueOf(CUTOFF.getEpochSecond()));

    assertThat(store.recordCutoff(7L, CUTOFF, RETENTION)).isFalse();

    assertThat(pttl()).isPositive().isLessThanOrEqualTo(RETENTION.toMillis());
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1000})
  @DisplayName("보관 TTL이 0 이하면 예외이고 키를 만들지 않는다")
  void nonPositiveTtlRejected(long ttlMillis) {
    assertThatThrownBy(() -> store.recordCutoff(7L, CUTOFF, Duration.ofMillis(ttlMillis)))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(redisTemplate.hasKey(KEY)).isFalse();
  }

  private long pttl() {
    Long ttl = redisTemplate.getExpire(KEY, TimeUnit.MILLISECONDS);
    return ttl == null ? -2 : ttl;
  }
}

package com.justcommit.backend.member.auth.infrastructure.redis;

import com.justcommit.backend.common.security.TokenHash;
import com.justcommit.backend.member.auth.infrastructure.redis.PasswordResetTokenStore.ResetTokenOwner;
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
 * 비밀번호 재설정 토큰 저장소를 실제 Redis로 확인한다 (#412, GETDEL 동작).
 * 스프링 컨텍스트 없이 Redis 연결만 직접 만든다. Docker가 없으면 건너뜀.
 */
@Testcontainers(disabledWithoutDocker = true)
class PasswordResetTokenStoreTest {

  @DynamicPropertySource
  static void containers(DynamicPropertyRegistry registry) {
    SharedRedis.register(registry);
  }

  private static final String TOKEN = "reset-token-for-test";
  private static final String KEY = "auth:password-reset:" + TokenHash.sha256Hex(TOKEN);
  private static final Duration TTL = Duration.ofMinutes(30);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate redisTemplate;

  private PasswordResetTokenStore store;

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
    store = new PasswordResetTokenStore(redisTemplate);
  }

  @Test
  @DisplayName("원문이 아니라 해시를 키로, 회원 id · 인증 버전을 30분 저장한다")
  void save() {
    store.save(TOKEN, 7L, 3, TTL);

    assertThat(redisTemplate.opsForValue().get(KEY)).isEqualTo("7:3");
    assertThat(redisTemplate.getExpire(KEY)).isBetween(1L, 1800L);
    assertThat(redisTemplate.hasKey("auth:password-reset:" + TOKEN)).isFalse();
  }

  @Test
  @DisplayName("한 번 꺼내면 지워져서 같은 토큰으로 다시 꺼낼 수 없다")
  void take_once() {
    store.save(TOKEN, 7L, 3, TTL);

    assertThat(store.take(TOKEN)).contains(new ResetTokenOwner(7L, 3));
    assertThat(store.take(TOKEN)).isEmpty();
    assertThat(redisTemplate.hasKey(KEY)).isFalse();
  }

  @Test
  @DisplayName("모르는 토큰이나 깨진 값은 빈 값")
  void take_unknownOrBroken() {
    assertThat(store.take("unknown-token")).isEmpty();

    redisTemplate.opsForValue().set(KEY, "broken", TTL);
    assertThat(store.take(TOKEN)).isEmpty();
  }
}

package com.justcommit.backend.member.auth.infrastructure.redis;

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
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 블랙리스트 저장을 실제 Redis로 확인한다. 키 형식은 Gateway(TokenRevocationChecker)가 읽는 형식과 같아야 한다.
 * Docker가 없으면 건너뜀.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccessTokenBlacklistStoreTest {

  // SHA-256("abc") 표준 테스트 벡터 → 키 형식을 해시 도구와 독립적으로 확인
  private static final String SHA256_OF_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
  private static final String KEY_OF_ABC = "auth:blacklist:" + SHA256_OF_ABC;

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate redisTemplate;

  private AccessTokenBlacklistStore store;

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
    store = new AccessTokenBlacklistStore(redisTemplate);
  }

  @Test
  @DisplayName("Gateway와 같은 키(auth:blacklist:{SHA-256})에 1을 저장하고, TTL은 남은 수명과 몇 초 안쪽으로 같다")
  void addStoresGatewayKeyWithRemainingTtl() {
    Duration lifetime = Duration.ofMinutes(30);

    boolean added = store.add("abc", Instant.now().plus(lifetime));

    assertThat(added).isTrue();
    assertThat(redisTemplate.opsForValue().get(KEY_OF_ABC)).isEqualTo("1");
    // 상한만 보면 즉시 만료 · 너무 짧은 TTL 버그도 통과하므로 하한도 확인 (테스트 실행 지연 5초 허용)
    assertThat(redisTemplate.getExpire(KEY_OF_ABC)).isBetween(lifetime.toSeconds() - 5, lifetime.toSeconds());
  }

  @Test
  @DisplayName("이미 지난 exp면 저장하지 않는다")
  void expiredTokenNotStored() {
    boolean added = store.add("abc", Instant.now().minusSeconds(1));

    assertThat(added).isFalse();
    assertThat(redisTemplate.hasKey(KEY_OF_ABC)).isFalse();
  }
}

package com.justcommit.backend.member.account.infrastructure;

import com.justcommit.backend.common.security.TokenHash;
import com.justcommit.backend.member.auth.domain.VerificationBinding;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계좌 변경 토큰 저장소를 실제 Redis로 확인한다 (#413).
 * 스프링 컨텍스트 없이 Redis 연결만 직접 만든다. Docker가 없으면 건너뜀.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountChangeTokenStoreTest {

  private static final String TOKEN = "account-change-token-for-test";
  private static final String KEY = "auth:account-change:" + TokenHash.sha256Hex(TOKEN);
  private static final Duration TTL = Duration.ofMinutes(10);

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate redisTemplate;

  private AccountChangeTokenStore store;

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
    store = new AccountChangeTokenStore(redisTemplate);
  }

  @Test
  @DisplayName("원문이 아니라 해시를 키로, 회원 id · 인증 버전을 10분 저장한다")
  void save() {
    store.save(TOKEN, new VerificationBinding(7L, 3), TTL);

    assertThat(redisTemplate.opsForValue().get(KEY)).isEqualTo("7:3");
    assertThat(redisTemplate.getExpire(KEY)).isBetween(1L, 600L);
    assertThat(redisTemplate.hasKey("auth:account-change:" + TOKEN)).isFalse();
  }
}

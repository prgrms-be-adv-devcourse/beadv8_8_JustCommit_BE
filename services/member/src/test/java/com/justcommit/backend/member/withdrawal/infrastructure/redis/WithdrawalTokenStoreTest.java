package com.justcommit.backend.member.withdrawal.infrastructure.redis;

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
 * 탈퇴 토큰 저장소를 실제 Redis로 확인한다 (#407). 스프링 컨텍스트 없이 Redis 연결만 직접 만든다.
 * 다른 회원의 토큰 · 인증 버전 변경 · 재사용 거절은 토큰을 실제로 쓰는 최종 탈퇴 테스트에서 확인한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class WithdrawalTokenStoreTest {

  private static final String TOKEN = "withdrawal-token-for-test";
  private static final String KEY = "auth:withdrawal:" + TokenHash.sha256Hex(TOKEN);
  private static final Duration TTL = Duration.ofMinutes(10);

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate redisTemplate;

  private WithdrawalTokenStore store;

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
    store = new WithdrawalTokenStore(redisTemplate);
  }

  @Test
  @DisplayName("원문이 아니라 해시를 키로, 회원 id · 인증 버전을 10분 저장한다")
  void save() {
    store.save(TOKEN, new VerificationBinding(7L, 3), TTL);

    assertThat(redisTemplate.opsForValue().get(KEY)).isEqualTo("7:3");
    assertThat(redisTemplate.getExpire(KEY)).isBetween(1L, 600L);
    assertThat(redisTemplate.hasKey("auth:withdrawal:" + TOKEN)).isFalse();
  }

  @Test
  @DisplayName("조회(find)는 지우지 않아 여러 번 읽을 수 있다")
  void find_keepsToken() {
    store.save(TOKEN, new VerificationBinding(7L, 3), TTL);

    assertThat(store.find(TOKEN)).contains(new VerificationBinding(7L, 3));
    assertThat(store.find(TOKEN)).contains(new VerificationBinding(7L, 3));
    assertThat(redisTemplate.hasKey(KEY)).isTrue();
  }

  @Test
  @DisplayName("꺼내기(take)는 지우면서 읽어 같은 토큰을 한 번만 쓸 수 있다")
  void take_once() {
    store.save(TOKEN, new VerificationBinding(7L, 3), TTL);

    assertThat(store.take(TOKEN)).contains(new VerificationBinding(7L, 3));
    assertThat(store.take(TOKEN)).isEmpty();
    assertThat(store.find(TOKEN)).isEmpty();
  }

  @Test
  @DisplayName("모르는 토큰이나 깨진 값은 빈 값")
  void unknownOrBroken() {
    assertThat(store.find("unknown-token")).isEmpty();
    assertThat(store.take("unknown-token")).isEmpty();

    redisTemplate.opsForValue().set(KEY, "broken", TTL);
    assertThat(store.find(TOKEN)).isEmpty();
  }
}

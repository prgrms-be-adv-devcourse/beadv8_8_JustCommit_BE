package com.justcommit.backend.member.auth.infrastructure.redis;

import com.justcommit.backend.common.security.TokenHash;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * Refresh 토큰 저장소를 실제 Redis로 확인한다 (Lua 스크립트 동작은 가짜 Redis로 확인할 수 없음).
 * 스프링 컨텍스트 없이 Redis 연결만 직접 만든다. Docker가 없으면 건너뜀.
 */
@Testcontainers(disabledWithoutDocker = true)
class RefreshTokenRedisStoreTest {

  // SHA-256("abc")는 표준 테스트 벡터라 값이 고정되어 있음
  private static final String SHA256_OF_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
  private static final Duration TTL = Duration.ofDays(1);

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate redisTemplate;

  private RefreshTokenRedisStore store;

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
    store = new RefreshTokenRedisStore(redisTemplate);
  }

  @Test
  @DisplayName("저장: SHA-256 해시를 키로 회원 id를 저장하고, 회원별 Set에도 해시를 넣으며 둘 다 TTL이 있다")
  void save() {
    store.save("abc", 4L, TTL);

    assertThat(redisTemplate.opsForValue().get("auth:refresh:" + SHA256_OF_ABC)).isEqualTo("4");
    assertThat(redisTemplate.opsForSet().members("auth:refresh-set:4")).containsExactly(SHA256_OF_ABC);
    assertThat(redisTemplate.getExpire("auth:refresh:" + SHA256_OF_ABC)).isBetween(1L, TTL.toSeconds());
    assertThat(redisTemplate.getExpire("auth:refresh-set:4")).isBetween(1L, TTL.toSeconds());
  }

  @Test
  @DisplayName("같은 토큰을 동시에 꺼내며 삭제하면 한 요청만 memberId를 얻고, 키와 Set에서 모두 빠진다")
  void concurrentTakeOnlyOneWins() throws Exception {
    store.save("token-a", 4L, TTL);

    List<Optional<Long>> results = runTogether(
        () -> store.findAndDelete("token-a"),
        () -> store.findAndDelete("token-a"));

    assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
    assertThat(results).filteredOn(Optional::isPresent).first().isEqualTo(Optional.of(4L));
    assertThat(redisTemplate.hasKey("auth:refresh:" + TokenHash.sha256Hex("token-a"))).isFalse();
    assertThat(redisTemplate.opsForSet().members("auth:refresh-set:4")).isEmpty();
  }

  @Test
  @DisplayName("전체 삭제가 목록을 읽은 뒤 새 토큰이 저장되면, 기존 토큰만 삭제되고 새 토큰은 키와 Set에 남는다")
  @SuppressWarnings("unchecked")
  void tokenSavedAfterReadSurvivesDeleteAll() {
    store.save("old-token", 7L, TTL);

    // 목록 읽기(members)가 끝난 직후 새 토큰을 저장하도록 끼워 넣음 → 매번 같은 순서로 재현
    SetOperations<String, String> realSetOps = redisTemplate.opsForSet();
    SetOperations<String, String> setOps = mock(SetOperations.class);
    given(setOps.members(anyString())).willAnswer(invocation -> {
      Set<String> read = realSetOps.members(invocation.getArgument(0));
      store.save("new-token", 7L, TTL);
      return read;
    });
    StringRedisTemplate racingTemplate = spy(redisTemplate);
    doReturn(setOps).when(racingTemplate).opsForSet();

    int deleted = new RefreshTokenRedisStore(racingTemplate).deleteAllByMember(7L);

    assertThat(deleted).isEqualTo(1);
    assertThat(redisTemplate.hasKey("auth:refresh:" + TokenHash.sha256Hex("old-token"))).isFalse();
    assertThat(redisTemplate.opsForValue().get("auth:refresh:" + TokenHash.sha256Hex("new-token"))).isEqualTo("7");
    assertThat(redisTemplate.opsForSet().members("auth:refresh-set:7")).containsExactly(TokenHash.sha256Hex("new-token"));
  }

  @Test
  @DisplayName("전체 삭제는 그 회원의 조회된 토큰만 지우고 다른 회원의 토큰은 남긴다")
  void deleteAllOnlyThatMember() {
    store.save("member7-a", 7L, TTL);
    store.save("member7-b", 7L, TTL);
    store.save("member8-a", 8L, TTL);

    int deleted = store.deleteAllByMember(7L);

    assertThat(deleted).isEqualTo(2);
    assertThat(redisTemplate.hasKey("auth:refresh:" + TokenHash.sha256Hex("member7-a"))).isFalse();
    assertThat(redisTemplate.hasKey("auth:refresh:" + TokenHash.sha256Hex("member7-b"))).isFalse();
    assertThat(redisTemplate.opsForSet().members("auth:refresh-set:7")).isEmpty();
    assertThat(redisTemplate.opsForValue().get("auth:refresh:" + TokenHash.sha256Hex("member8-a"))).isEqualTo("8");
    assertThat(redisTemplate.opsForSet().members("auth:refresh-set:8")).containsExactly(TokenHash.sha256Hex("member8-a"));
  }

  // 두 작업을 같은 순간에 시작시키고 결과를 모은다 (실제로 겹쳐 실행된다는 보장은 없음)
  private static <T> List<T> runTogether(Callable<T> first, Callable<T> second) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<T>> futures = new ArrayList<>();
    try {
      for (Callable<T> task : List.of(first, second)) {
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
      futures.forEach(future -> future.cancel(true));
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }
}

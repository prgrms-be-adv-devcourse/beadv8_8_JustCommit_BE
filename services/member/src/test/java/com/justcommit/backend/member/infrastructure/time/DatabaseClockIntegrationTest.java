package com.justcommit.backend.member.infrastructure.time;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DatabaseClock을 실제 PostgreSQL로 확인한다 (모의 객체로는 SQL 실행 · 연결 공유를 확인할 수 없음).
 * clock_timestamp()의 동작을 확인할 뿐, DB 시계가 역행하지 않는다는 보장은 아니다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class DatabaseClockIntegrationTest {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
  }

  @Autowired
  private DatabaseClock databaseClock;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private EntityManager entityManager;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Test
  @DisplayName("DB 현재 시각을 Instant로 읽는다")
  void readsDatabaseTime() {
    Instant now = databaseClock.now();

    // 컨테이너와 테스트 프로세스는 같은 컴퓨터 시계를 쓰므로 크게 다르지 않음
    assertThat(Duration.between(now, Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
  }

  @Test
  @DisplayName("트랜잭션 안에서 JDBC(DatabaseClock이 쓰는 JdbcTemplate)와 JPA가 같은 DB 트랜잭션을 쓴다")
  void sharesTransactionWithJpa() {
    transactionTemplate.executeWithoutResult(status -> {
      long jpaTransactionId = ((Number) entityManager
          .createNativeQuery("select txid_current()")
          .getSingleResult()).longValue();
      Long jdbcTransactionId = jdbcTemplate.queryForObject("select txid_current()", Long.class);

      assertThat(jdbcTransactionId).isEqualTo(jpaTransactionId);
    });
  }

  @Test
  @DisplayName("같은 트랜잭션 안에서 now()는 고정되지만, DatabaseClock은 DB 대기 뒤 더 늦은 시각을 읽는다")
  void readsClockTimestampInsideTransaction() {
    transactionTemplate.executeWithoutResult(status -> {
      Instant transactionStart = transactionStart();
      Instant first = databaseClock.now();
      // 연속 호출은 같은 값일 수 있어 DB에서 잠깐 기다림
      jdbcTemplate.queryForList("select pg_sleep(0.05)");
      Instant second = databaseClock.now();

      // 같은 트랜잭션이라 now()는 그대로
      assertThat(transactionStart()).isEqualTo(transactionStart);
      // clock_timestamp()는 호출한 순간 → 트랜잭션 시작 시각이 아님
      assertThat(first).isAfterOrEqualTo(transactionStart);
      assertThat(second).isAfter(first);
    });
  }

  private Instant transactionStart() {
    return jdbcTemplate.queryForObject("select now()", OffsetDateTime.class).toInstant();
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

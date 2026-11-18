package com.justcommit.backend.member.withdrawal.infrastructure.repository;

import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.withdrawal.domain.MemberWithdrawalRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 탈퇴를 위한 스키마 (#407, Flyway V8)를 실제 PostgreSQL로 확인한다.
 * - 컨텍스트가 뜨면 V8 적용과 Hibernate validate(ddl-auto: validate)는 통과한 것
 * - 이 클래스에는 @Transactional이 없어 리포지토리 호출마다 새 영속성 컨텍스트를 쓴다.
 *   그래서 saveAndFlush 뒤의 findById는 메모리 객체가 아니라 DB에서 다시 읽은 값이다
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class MemberWithdrawalSchemaIntegrationTest {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final AtomicInteger SEQ = new AtomicInteger();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @Autowired
  private MemberWithdrawalRecordRepository recordRepository;

  @Autowired
  private MemberRepository memberRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("탈퇴 기록을 저장하고 DB에서 다시 읽으면 해시와 탈퇴 시각(마이크로초까지)이 같다")
  void saveAndReadWithdrawalRecord() {
    // TIMESTAMP(6)은 마이크로초까지 저장하므로, 나노초가 없는 고정 시각으로 비교한다
    Instant withdrawnAt = Instant.parse("2026-11-18T01:02:03.123456Z");
    String emailHash = "a".repeat(64);
    String phoneHash = "b".repeat(64);

    MemberWithdrawalRecord saved = recordRepository.saveAndFlush(
        MemberWithdrawalRecord.create(emailHash, phoneHash, withdrawnAt));

    MemberWithdrawalRecord found = recordRepository.findById(saved.getId()).orElseThrow();
    assertThat(found.getEmailHash()).isEqualTo(emailHash);
    assertThat(found.getPhoneHash()).isEqualTo(phoneHash);
    assertThat(found.getWithdrawnAt()).isEqualTo(withdrawnAt);
    assertThat(found.getCreatedAt()).isNotNull();
  }

  @Test
  @DisplayName("익명화 대상 6개 컬럼은 NULL로 바꿀 수 있고, 보존 컬럼(role · provider · status)은 그대로다")
  void anonymizationColumnsAcceptNull() {
    int n = SEQ.incrementAndGet();
    Member member = memberRepository.save(Member.createLocal(
        "schema" + n + "@test.com", "encoded-password", "스키마" + n, String.format("014%08d", n),
        "004", "v1:test-account", null, "홍길동"));

    jdbcTemplate.update("""
        update member
           set nickname = null, phone = null, bank_code = null,
               account_no_enc = null, account_holder = null, account_updated_at = null
         where id = ?
        """, member.getId());

    Map<String, Object> row = jdbcTemplate.queryForMap("""
        select nickname, phone, bank_code, account_no_enc, account_holder, account_updated_at,
               role, provider, status
          from member
         where id = ?
        """, member.getId());

    assertThat(row.get("nickname")).isNull();
    assertThat(row.get("phone")).isNull();
    assertThat(row.get("bank_code")).isNull();
    assertThat(row.get("account_no_enc")).isNull();
    assertThat(row.get("account_holder")).isNull();
    assertThat(row.get("account_updated_at")).isNull();
    assertThat(row.get("role")).isNotNull();
    assertThat(row.get("provider")).isNotNull();
    assertThat(row.get("status")).isNotNull();
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

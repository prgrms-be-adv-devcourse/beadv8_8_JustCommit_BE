package com.justcommit.backend.member.infrastructure.repository;

import com.justcommit.backend.member.domain.Member;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 계좌 HMAC 칼럼의 UNIQUE 제약 (#413)을 실제 PostgreSQL로 확인한다.
 * - 가입 서비스는 DB 오류 메시지의 제약 이름(uk_member_account_hash)으로 DUPLICATE_ACCOUNT를 판단하므로,
 *   실제 메시지에 그 이름이 나오는지 본다 (메시지 → DUPLICATE_ACCOUNT 변환은 가입 서비스 단위 테스트에서 확인)
 * - 기존 회원처럼 HMAC이 NULL인 회원은 여러 명 저장된다
 * - 테스트에 @Transactional을 붙이지 않는다: 저장마다 저장소의 트랜잭션이 따로 끝나, UNIQUE 위반이 그 저장만 실패시킨다
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class MemberAccountHashIntegrationTest {

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

  @Autowired MemberRepository memberRepository;
  @Autowired JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from member");
  }

  @Test
  @DisplayName("다른 회원이 같은 계좌 HMAC으로 저장하면 UNIQUE 위반, 원인 메시지에 uk_member_account_hash")
  void sameAccountHash_violatesUnique() {
    memberRepository.saveAndFlush(member("same-account-hash"));

    assertThatThrownBy(() -> memberRepository.saveAndFlush(member("same-account-hash")))
        .isInstanceOfSatisfying(DataIntegrityViolationException.class,
            e -> assertThat(e.getMostSpecificCause().getMessage()).contains("uk_member_account_hash"));
  }

  @Test
  @DisplayName("계좌 HMAC이 NULL인 회원은 여러 명 저장된다 (기존 회원)")
  void nullAccountHash_allowsMany() {
    memberRepository.saveAndFlush(member(null));
    memberRepository.saveAndFlush(member(null));

    assertThat(memberRepository.count()).isEqualTo(2);
  }

  // 이메일 · 닉네임 · 휴대폰은 회원마다 달라 다른 UNIQUE 제약에 걸리지 않게 한다
  private Member member(String accountHash) {
    int n = SEQ.incrementAndGet();
    return Member.createLocal("hash" + n + "@test.com", "encoded", "해시" + n, String.format("018%08d", n),
        "004", "v1:test-account", accountHash, "홍길동");
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

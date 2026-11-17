package com.justcommit.backend.member.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.messaging.outbox.OutboxRelay;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 닉네임 변경 (#414)을 실제 PostgreSQL로 확인한다.
 * - 커밋 뒤 Kafka 전송(OutboxRelay)만 막고, Outbox 행 저장은 실제로 한다
 * - 준비 데이터는 커밋한 뒤 서비스를 호출한다 (클래스 · 메서드에 @Transactional 없음)
 */
@SpringBootTest(properties = "outbox.scheduler.enabled=false")
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SuppressWarnings("unchecked")
class NicknameChangeServiceIntegrationTest {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final Duration WAIT = Duration.ofSeconds(10);
  private static final Duration LOCK_TIMEOUT = Duration.ofMillis(500);
  private static final AtomicInteger SEQ = new AtomicInteger();

  @DynamicPropertySource
  static void jwtKeys(DynamicPropertyRegistry registry) {
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
    registry.add("member.lock.timeout", () -> LOCK_TIMEOUT.toMillis() + "ms");
  }

  // 커밋 뒤 Kafka 전송만 막는다 (Outbox 행 저장은 실제)
  @MockitoBean
  OutboxRelay outboxRelay;

  @Autowired NicknameChangeService nicknameChangeService;
  @Autowired MemberRepository memberRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired TransactionTemplate transactionTemplate;

  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from outbox_event");
    jdbcTemplate.update("delete from seller");
    jdbcTemplate.update("delete from address");
    jdbcTemplate.update("delete from member");
  }

  @Test
  @DisplayName("[S-B02] 닉네임을 바꾸면 회원 닉네임이 바뀌고, Outbox에 바뀐 뒤 전체 값의 MemberUpdated가 남는다")
  void changeNickname() {
    Member member = saveMember("before" + SEQ.incrementAndGet());

    NicknameChangeResult result = nicknameChangeService.changeNickname(member.getId(), "새닉네임");

    assertThat(result).isEqualTo(new NicknameChangeResult(member.getId(), "새닉네임"));
    assertThat(nicknameOf(member.getId())).isEqualTo("새닉네임");
    Map<String, Object> payload = onlyMemberUpdatedPayload(member.getId());
    assertThat(((Number) payload.get("memberId")).longValue()).isEqualTo(member.getId());
    assertThat(payload.get("nickname")).isEqualTo("새닉네임");
    assertThat(payload.get("role")).isEqualTo("MEMBER");
    assertThat(payload.get("seller")).isEqualTo(false);
  }

  @Test
  @DisplayName("판매자 회원이 바꾸면 MemberUpdated의 seller는 true, role은 SELLER")
  void changeNicknameOfSeller() {
    Member member = saveMember("seller" + SEQ.incrementAndGet());
    jdbcTemplate.update("update member set role = 'SELLER' where id = ?", member.getId());
    jdbcTemplate.update("insert into seller (member_id, intro, created_at, updated_at) values (?, null, now(), now())",
        member.getId());

    nicknameChangeService.changeNickname(member.getId(), "판매자닉");

    Map<String, Object> payload = onlyMemberUpdatedPayload(member.getId());
    assertThat(payload.get("nickname")).isEqualTo("판매자닉");
    assertThat(payload.get("role")).isEqualTo("SELLER");
    assertThat(payload.get("seller")).isEqualTo(true);
  }

  @Test
  @DisplayName("지금 닉네임과 같으면 그대로 두고 이벤트를 보내지 않는다")
  void sameNicknameIsNoOp() {
    String nickname = "same" + SEQ.incrementAndGet();
    Member member = saveMember(nickname);

    NicknameChangeResult result = nicknameChangeService.changeNickname(member.getId(), nickname);

    assertThat(result).isEqualTo(new NicknameChangeResult(member.getId(), nickname));
    assertThat(outboxCount()).isZero();
  }

  @Test
  @DisplayName("다른 회원이 쓰는 닉네임이면 409 DUPLICATE_NICKNAME, 닉네임 · 이벤트 모두 그대로")
  void duplicateNicknameRejected() {
    String original = "mine" + SEQ.incrementAndGet();
    Member member = saveMember(original);
    saveMember("taken" + SEQ.incrementAndGet());
    String taken = nicknameOf(memberRepository.findAll().stream()
        .filter(m -> !m.getId().equals(member.getId())).findFirst().orElseThrow().getId());

    assertThatThrownBy(() -> nicknameChangeService.changeNickname(member.getId(), taken))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.DUPLICATE_NICKNAME);

    assertThat(nicknameOf(member.getId())).isEqualTo(original);
    assertThat(outboxCount()).isZero();
  }

  @Test
  @DisplayName("다른 회원이 같은 닉네임을 커밋 전에 저장하고 회원 잠금 대기 제한보다 오래 붙잡아도, 중복 확인은 통과하고 DB UNIQUE 위반을 409 DUPLICATE_NICKNAME으로 바꾼다")
  void concurrentSameNicknameBecomesDuplicate() throws Exception {
    String original = "race" + SEQ.incrementAndGet();
    Member member = saveMember(original);
    String contested = "경쟁" + SEQ.incrementAndGet();
    CountDownLatch inserted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      // 다른 회원이 같은 닉네임으로 저장만 하고 커밋하지 않은 채 붙잡음
      Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
        memberRepository.saveAndFlush(newMember(contested));
        inserted.countDown();
        await(release);
      }));
      assertThat(inserted.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

      // 커밋 전이라 중복 확인은 통과 → flush 때 UNIQUE 확인이 상대 트랜잭션을 기다림
      Future<NicknameChangeResult> changer = executor.submit(
          () -> nicknameChangeService.changeNickname(member.getId(), contested));
      waitUntilSomeoneWaitsForLock();

      // 회원 잠금 대기 제한(500ms)보다 오래 붙잡아도 저장 단계(UNIQUE 확인)는 제한 없이 기다려야 함 (#419)
      Thread.sleep(LOCK_TIMEOUT.multipliedBy(2).toMillis());
      assertThat(changer).isNotDone();

      release.countDown();
      holder.get(WAIT.toSeconds(), TimeUnit.SECONDS);

      assertThatThrownBy(() -> changer.get(WAIT.toSeconds(), TimeUnit.SECONDS))
          .hasCauseInstanceOf(BusinessException.class)
          .cause().extracting("errorCode").isEqualTo(MemberErrorCode.DUPLICATE_NICKNAME);
    } finally {
      release.countDown();
      executor.shutdownNow();
    }

    assertThat(nicknameOf(member.getId())).isEqualTo(original);
    assertThat(outboxCount()).isZero();
  }

  @Test
  @DisplayName("탈퇴 회원이면 MEMBER_NOT_FOUND")
  void withdrawnMemberNotFound() {
    Member member = saveMember("gone" + SEQ.incrementAndGet());
    jdbcTemplate.update("update member set status = 'DELETED' where id = ?", member.getId());

    assertThatThrownBy(() -> nicknameChangeService.changeNickname(member.getId(), "새닉네임"))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
  }

  // 다른 세션이 잠금(UNIQUE 확인 대기 포함)을 기다리는 상태가 될 때까지 기다림
  private void waitUntilSomeoneWaitsForLock() throws InterruptedException {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (System.nanoTime() < deadline) {
      Long waiting = jdbcTemplate.queryForObject(
          "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'",
          Long.class);
      if (waiting != null && waiting > 0) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("닉네임 변경이 UNIQUE 확인 대기 상태가 되지 않음");
  }

  private Map<String, Object> onlyMemberUpdatedPayload(Long memberId) {
    List<String> rows = jdbcTemplate.queryForList(
        "select payload from outbox_event where event_type = 'MemberUpdated' and aggregate_id = ?",
        String.class, String.valueOf(memberId));
    assertThat(rows).hasSize(1);
    Map<String, Object> envelope = jsonMapper.readValue(rows.get(0), Map.class);
    return (Map<String, Object>) envelope.get("payload");
  }

  private long outboxCount() {
    Long count = jdbcTemplate.queryForObject("select count(*) from outbox_event", Long.class);
    return count == null ? 0 : count;
  }

  private String nicknameOf(Long memberId) {
    return jdbcTemplate.queryForObject("select nickname from member where id = ?", String.class, memberId);
  }

  private Member saveMember(String nickname) {
    return memberRepository.save(newMember(nickname));
  }

  private Member newMember(String nickname) {
    int n = SEQ.incrementAndGet();
    return Member.createLocal("nick" + n + "@test.com", passwordEncoder.encode("plant1234"), nickname,
        String.format("015%08d", n), "004", "v1:test-account", null, "홍길동");
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(WAIT.toSeconds(), TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
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

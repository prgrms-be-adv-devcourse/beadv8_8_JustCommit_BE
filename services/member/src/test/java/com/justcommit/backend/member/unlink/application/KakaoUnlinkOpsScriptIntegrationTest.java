package com.justcommit.backend.member.unlink.application;

import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.common.testcontainers.SharedRedis;
import com.justcommit.backend.member.auth.application.SocialLoginCommand;
import com.justcommit.backend.member.auth.application.SocialLoginService;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
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
 * 운영 스크립트 (#422, docs/infra/kakao-unlink-runbook.md): src/main/resources/ops의 재개 · 종료 SQL을 운영과 같은 방식
 * (같은 트랜잭션에서 set_config로 기록 id → 파일 그대로 실행)으로 실행한다.
 * 재개 단계별 조건, 조건 불일치 시 전부 롤백, 재로그인과 겹쳐도 재로그인의 무효화가 이기는지 확인한다
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class KakaoUnlinkOpsScriptIntegrationTest {

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final AtomicInteger SEQ = new AtomicInteger();
  private static final String RESUME = "ops/kakao-unlink-resume.sql";
  private static final String CLOSE = "ops/kakao-unlink-close.sql";

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    SharedPostgres.register(registry);
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
    SharedRedis.register(registry);
  }

  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired TransactionTemplate transactionTemplate;
  @Autowired MemberRepository memberRepository;
  @Autowired SocialLoginService socialLoginService;
  @Autowired StringRedisTemplate redisTemplate;

  private final List<Long> memberIds = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from member_kakao_unlink_receipt");
    for (Long id : memberIds) {
      jdbcTemplate.update("delete from member where id = ?", id);
    }
    try (var connection = redisTemplate.getRequiredConnectionFactory().getConnection()) {
      connection.serverCommands().flushAll();
    }
  }

  @Test
  @DisplayName("[#422] PENDING 재개: 회원이 아직 연결되지 않아도 회원번호로 찾아 재개(ACTIVE가 정상), 처리권 +1 · 실패 0 · 바로 처리 대상")
  void resumePending() throws IOException {
    kakaoMember("9500000001", "ACTIVE", 0);
    Long id = failed("9500000001", "PENDING", null, null, false);

    run(RESUME, id);

    assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("attempt_no", 4).containsEntry("failure_count", 0)
        .containsEntry("last_error", "KAKAO_UNAVAILABLE");
    assertThat(row(id).get("resume_state")).isNull();
    assertThat(row(id).get("next_attempt_at")).isNotNull();
  }

  @Test
  @DisplayName("[#422] PENDING인데 회원이 없거나 탈퇴했으면 재개는 거절(전부 롤백), 종료는 IGNORED(MEMBER_NOT_FOUND)")
  void pendingWithoutMemberIsIgnored() throws IOException {
    Long id = failed("9500000002", "PENDING", null, null, false);

    assertRejected(RESUME, id, "회원이 없거나 탈퇴함");
    assertThat(row(id)).containsEntry("status", "FAILED").containsEntry("attempt_no", 3);

    run(CLOSE, id);
    assertThat(row(id)).containsEntry("status", "IGNORED").containsEntry("last_error", "MEMBER_NOT_FOUND")
        .containsEntry("attempt_no", 4);
    assertThat(row(id).get("resume_state")).isNull();
  }

  @Test
  @DisplayName("[#422] PENDING이고 회원이 있으면(ACTIVE) 종료는 거절 — 아직 INACTIVE 전이라 ACTIVE가 정상")
  void pendingWithMemberIsNotClosed() throws IOException {
    kakaoMember("9500000003", "ACTIVE", 0);
    Long id = failed("9500000003", "PENDING", null, null, false);

    assertRejected(CLOSE, id, "끝낼 대상이 아님");
    assertThat(row(id)).containsEntry("status", "FAILED").containsEntry("attempt_no", 3);
  }

  @Test
  @DisplayName("[#422] INACTIVATED 재개: 회원이 INACTIVE이고 인증 버전 = 연결 해제 버전일 때만")
  void resumeInactivated() throws IOException {
    Long memberId = kakaoMember("9500000004", "INACTIVE", 1);
    Long id = failed("9500000004", "INACTIVATED", memberId, 1, true);

    run(RESUME, id);

    assertThat(row(id)).containsEntry("status", "INACTIVATED").containsEntry("attempt_no", 4)
        .containsEntry("handles_withdrawal", true);
  }

  @Test
  @DisplayName("[#422] INACTIVATED인데 재연결(ACTIVE)됐으면 재개는 거절, 종료는 INVALIDATED (오류 · 실패 횟수 · 처리 기록 표시는 그대로)")
  void inactivatedAfterReconnectIsInvalidated() throws IOException {
    Long memberId = kakaoMember("9500000005", "ACTIVE", 1);
    Long id = failed("9500000005", "INACTIVATED", memberId, 1, true);

    assertRejected(RESUME, id, "연결 해제 상태가 유효하지 않음");
    run(CLOSE, id);

    assertThat(row(id)).containsEntry("status", "INVALIDATED").containsEntry("attempt_no", 4)
        .containsEntry("failure_count", 5).containsEntry("last_error", "KAKAO_UNAVAILABLE")
        .containsEntry("handles_withdrawal", true);
    assertThat(row(id).get("next_attempt_at")).isNull();
  }

  @Test
  @DisplayName("[#422] 연결 해제 상태가 아직 유효하면(INACTIVE · 같은 버전) 종료는 거절 — 알림을 끄려는 종료는 하지 않음")
  void validUnlinkStateIsNotClosed() throws IOException {
    Long memberId = kakaoMember("9500000006", "INACTIVE", 1);
    Long id = failed("9500000006", "NOTICE_PENDING", memberId, 1, true);

    assertRejected(CLOSE, id, "아직 유효함");
    assertThat(row(id)).containsEntry("status", "FAILED");
  }

  @Test
  @DisplayName("[#422] 안내 대기 재개는 자동 탈퇴 처리 기록일 때만, FAILED가 아닌 기록은 두 스크립트 모두 거절")
  void otherRejections() throws IOException {
    Long memberId = kakaoMember("9500000007", "INACTIVE", 1);
    Long notice = failed("9500000007", "NOTICE_PENDING", memberId, 1, false);
    assertRejected(RESUME, notice, "자동 탈퇴 처리 기록이 아님");

    jdbcTemplate.update("update member_kakao_unlink_receipt set status = 'INVALIDATED', resume_state = null where id = ?", notice);
    assertRejected(RESUME, notice, "FAILED가 아님");
    assertRejected(CLOSE, notice, "FAILED가 아님");
  }

  @Test
  @DisplayName("[#422] 재개와 재로그인이 겹쳐도(재개가 회원을 먼저 잠금) 재로그인은 기다렸다가 기록을 무효화하고 회원을 복구한다")
  void resumeThenReloginStillInvalidates() throws Exception {
    Long memberId = kakaoMember("9500000008", "INACTIVE", 1);
    Long id = failed("9500000008", "INACTIVATED", memberId, 1, true);
    CountDownLatch resumed = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      // 재개 스크립트가 회원 · 기록을 잠근 채 잠시 커밋을 늦춘다
      Future<?> script = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
        execute(RESUME, id);
        resumed.countDown();
        sleep(500);
      }));
      assertThat(resumed.await(10, TimeUnit.SECONDS)).isTrue();

      // 그 사이 실제 카카오 재로그인: 회원 잠금을 기다렸다가 재개된 기록까지 무효화
      socialLoginService.login(new SocialLoginCommand(Provider.KAKAO, "9500000008", "ops8@kakao.com"));
      script.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    assertThat(memberRepository.findById(memberId).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
    assertThat(row(id)).containsEntry("status", "INVALIDATED").containsEntry("attempt_no", 4);
  }

  // 운영과 같은 방식: 한 트랜잭션에서 기록 id를 트랜잭션 로컬 설정으로 넣고 파일을 그대로 실행
  private void run(String script, Long id) {
    transactionTemplate.executeWithoutResult(status -> execute(script, id));
  }

  private void execute(String script, Long id) {
    jdbcTemplate.queryForObject("select set_config('replant.ops.receipt_id', ?, true)", String.class, String.valueOf(id));
    jdbcTemplate.execute(read(script));
  }

  private void assertRejected(String script, Long id, String reason) {
    assertThatThrownBy(() -> run(script, id)).isInstanceOf(DataAccessException.class).hasMessageContaining(reason);
  }

  private static String read(String script) {
    try {
      return new String(new ClassPathResource(script).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private Long kakaoMember(String providerId, String status, int tokenVersion) {
    int n = SEQ.incrementAndGet();
    Member member = memberRepository.save(Member.createSocial(Provider.KAKAO, providerId,
        "opsscript" + n + "@test.com", "운영" + n, String.format("019%08d", 80_000_000 + n),
        "004", "v1:test-account", null, "홍길동"));
    memberIds.add(member.getId());
    jdbcTemplate.update("update member set status = ?, token_version = ? where id = ?", status, tokenVersion, member.getId());
    return member.getId();
  }

  // 재시도를 다 써서 멈춘 기록 (처리권 3, 실패 5)
  private Long failed(String providerId, String resumeState, Long memberId, Integer version, boolean handlesWithdrawal) {
    jdbcTemplate.update("""
        insert into member_kakao_unlink_receipt
            (provider_id, referrer_type, received_at, status, resume_state, member_id, unlink_version,
             handles_withdrawal, attempt_no, failure_count, last_error, created_at, updated_at)
        values (?, 'UNLINK_FROM_APPS', now(), 'FAILED', ?, ?, ?, ?, 3, 5, 'KAKAO_UNAVAILABLE', now(), now())
        """, providerId, resumeState, memberId, version, handlesWithdrawal);
    return jdbcTemplate.queryForObject("select max(id) from member_kakao_unlink_receipt", Long.class);
  }

  private Map<String, Object> row(Long id) {
    return jdbcTemplate.queryForMap("select * from member_kakao_unlink_receipt where id = ?", id);
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

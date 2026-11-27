package com.justcommit.backend.member.unlink.infrastructure.repository;

import com.justcommit.backend.member.support.MemberIntegrationTest;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.unlink.domain.KakaoUnlinkReceipt;
import com.justcommit.backend.member.unlink.domain.KakaoUnlinkStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 카카오 연결 해제 웹훅 수신 기록 스키마 (#422, Flyway V10)를 실제 PostgreSQL로 확인한다.
 * - 컨텍스트가 뜨면 V10 적용과 Hibernate validate는 통과한 것
 * - 제약 위반은 엔티티를 거치지 않고 SQL로 직접 넣어 DB가 막는지 본다
 */
class KakaoUnlinkReceiptSchemaIntegrationTest extends MemberIntegrationTest {

  private static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired
  private KakaoUnlinkReceiptRepository receiptRepository;

  @Autowired
  private MemberRepository memberRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  // 회원을 가리키는 기록이 남으면 다른 테스트의 회원 삭제가 FK에 막히므로 지운다
  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from member_kakao_unlink_receipt");
  }

  @Test
  @DisplayName("정상 수신 기록은 PENDING · 바로 처리 대상으로 저장되고, 처리 칼럼은 기본값(시도 0 · 실패 0 · 처리 기록 아님)")
  void saveReceived() {
    Instant now = Instant.parse("2026-11-19T01:02:03.123456Z");

    KakaoUnlinkReceipt saved = receiptRepository.saveAndFlush(
        KakaoUnlinkReceipt.received("9000000001", "UNLINK_FROM_APPS", now));

    KakaoUnlinkReceipt found = receiptRepository.findById(saved.getId()).orElseThrow();
    assertThat(found.getStatus()).isEqualTo(KakaoUnlinkStatus.PENDING);
    assertThat(found.getProviderId()).isEqualTo("9000000001");
    assertThat(found.getReferrerType()).isEqualTo("UNLINK_FROM_APPS");
    assertThat(found.getReceivedAt()).isEqualTo(now);
    assertThat(found.getNextAttemptAt()).isEqualTo(now);
    assertThat(found.getAttemptNo()).isZero();
    assertThat(found.getFailureCount()).isZero();
    assertThat(found.isHandlesWithdrawal()).isFalse();
    assertThat(found.getResumeState()).isNull();
  }

  @Test
  @DisplayName("잘못된 요청은 회원번호 없이도 IGNORED로 저장되고, 오류는 정해진 코드만 남는다")
  void saveIgnoredWithoutProvider() {
    KakaoUnlinkReceipt saved = receiptRepository.saveAndFlush(
        KakaoUnlinkReceipt.ignored(null, null, "INVALID_USER_ID", Instant.now()));

    KakaoUnlinkReceipt found = receiptRepository.findById(saved.getId()).orElseThrow();
    assertThat(found.getStatus()).isEqualTo(KakaoUnlinkStatus.IGNORED);
    assertThat(found.getProviderId()).isNull();
    assertThat(found.getLastError()).isEqualTo("INVALID_USER_ID");
  }

  @Test
  @DisplayName("회원번호 없이 처리 단계(PENDING)로는 저장할 수 없다")
  void pendingRequiresProvider() {
    assertViolation(() -> insert(null, "PENDING", null, null, null, false));
  }

  @Test
  @DisplayName("FAILED는 재개할 단계가 있어야 하고, FAILED가 아니면 재개할 단계를 가질 수 없다")
  void resumeStateOnlyWithFailed() {
    assertViolation(() -> insert("9000000002", "FAILED", null, null, null, false));
    assertViolation(() -> insert("9000000002", "PENDING", "PENDING", null, null, false));
    assertThatCode(() -> insert("9000000002", "FAILED", "INACTIVATED", null, null, false)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("자동 탈퇴 처리 기록은 회원과 연결 해제 상태(버전)가 있어야 한다")
  void withdrawalRecordRequiresMemberAndVersion() {
    Long memberId = memberId();

    assertViolation(() -> insert("9000000003", "INACTIVATED", null, null, 1, true));
    assertViolation(() -> insert("9000000003", "INACTIVATED", null, memberId, null, true));
  }

  @Test
  @DisplayName("같은 회원 · 같은 버전의 자동 탈퇴 처리 기록은 하나뿐이고, 처리 기록이 아닌 기록이나 다른 버전은 여럿 가능")
  void oneWithdrawalRecordPerUnlinkState() {
    Long memberId = memberId();
    insert("9000000004", "INACTIVATED", null, memberId, 1, true);

    assertViolation(() -> insert("9000000004", "INACTIVATED", null, memberId, 1, true));
    assertThatCode(() -> {
      insert("9000000004", "DUPLICATE", null, memberId, 1, false);
      insert("9000000004", "DUPLICATE", null, memberId, 1, false);
      insert("9000000004", "INACTIVATED", null, memberId, 2, true);
    }).doesNotThrowAnyException();
  }

  private void insert(String providerId, String status, String resumeState, Long memberId, Integer version,
                      boolean handlesWithdrawal) {
    jdbcTemplate.update("""
        insert into member_kakao_unlink_receipt
            (provider_id, referrer_type, received_at, status, resume_state, member_id, unlink_version,
             handles_withdrawal, created_at, updated_at)
        values (?, 'UNLINK_FROM_APPS', now(), ?, ?, ?, ?, ?, now(), now())
        """, providerId, status, resumeState, memberId, version, handlesWithdrawal);
  }

  private static void assertViolation(Runnable insert) {
    assertThatThrownBy(insert::run).isInstanceOf(DataIntegrityViolationException.class);
  }

  private Long memberId() {
    int n = SEQ.incrementAndGet();
    return memberRepository.save(Member.createLocal(
        "unlinkschema" + n + "@test.com", "encoded-password", "해제" + n, String.format("019%08d", 50_000_000 + n),
        "004", "v1:test-account", null, "홍길동")).getId();
  }
}

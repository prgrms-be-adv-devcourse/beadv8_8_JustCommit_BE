package com.justcommit.backend.member.unlink.application;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.common.testcontainers.SharedRedis;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.unlink.domain.KakaoUnlinkReceipt;
import com.justcommit.backend.member.unlink.infrastructure.repository.KakaoUnlinkReceiptRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S-B10] 카카오 연결 해제 웹훅 처리 (#422). 카카오 · 내부 서비스(3곳 확인)는 WireMock, DB · Redis는 실제.
 * ① PENDING: 기록만 대상 거르기 → 카카오 연결 조회 → INACTIVE + 토큰 폐기, 중복 폐기 없음, 재시도 · 실패, 이전 작업자 차단
 * ② INACTIVATED: 관리자 해제는 끝, 자동 탈퇴 사유는 처리 기록 하나만 3곳 확인 → 탈퇴 · 안내 대기 · 재시도
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class KakaoUnlinkProcessorIntegrationTest {

  @RegisterExtension
  static final WireMockExtension KAKAO = WireMockExtension.newInstance()
      .options(wireMockConfig().dynamicPort())
      .build();

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final AtomicInteger SEQ = new AtomicInteger();
  private static final String USER_ME = "/v2/user/me";
  private static final String PASS = """
      {"hasWithdrawalBlocker": false, "blockingReasons": [], "details": {}}""";
  private static final String BLOCK = """
      {"hasWithdrawalBlocker": true, "blockingReasons": ["ACTIVE_BUYER_ORDER"], "details": {"activeBuyerOrderCount": 1}}""";

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    SharedPostgres.register(registry);
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
    SharedRedis.register(registry);
    registry.add("member.kakao.api-base-url", KAKAO::baseUrl);
    // 3곳 확인(market · payment · settlement)도 같은 WireMock으로 (경로가 달라 섞이지 않음)
    registry.add("spring.http.serviceclient.market.base-url", KAKAO::baseUrl);
    registry.add("spring.http.serviceclient.payment.base-url", KAKAO::baseUrl);
    registry.add("spring.http.serviceclient.settlement.base-url", KAKAO::baseUrl);
  }

  @Autowired KakaoUnlinkProcessor processor;
  @Autowired KakaoUnlinkReceiptRepository receiptRepository;
  @Autowired MemberRepository memberRepository;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired StringRedisTemplate redisTemplate;

  // 탈퇴하면 이메일이 비워지므로 만든 회원 id로 정리한다
  private final List<Long> memberIds = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from member_kakao_unlink_receipt");
    for (Long id : memberIds) {
      jdbcTemplate.update("delete from member_token_revocation where member_id = ?", id);
      jdbcTemplate.update("delete from outbox_event where aggregate_id = ?", String.valueOf(id));
      jdbcTemplate.update("delete from member where id = ?", id);
    }
    try (var connection = redisTemplate.getRequiredConnectionFactory().getConnection()) {
      connection.serverCommands().flushAll();
    }
  }

  @Test
  @DisplayName("[S-B10] 가입 미완료(INCOMPLETE_SIGN_UP)는 카카오 조회 없이 IGNORED(NOT_TARGET)")
  void incompleteSignUpIgnored() {
    Long receiptId = receive("9200000001", "INCOMPLETE_SIGN_UP");

    assertThat(processor.processDue()).isEqualTo(1);

    assertThat(row(receiptId)).containsEntry("status", "IGNORED").containsEntry("last_error", "NOT_TARGET");
    KAKAO.verify(0, anyRequestedFor(anyUrl()));
  }

  @Test
  @DisplayName("[S-B10] 회원이 없으면(이미 탈퇴 포함) 카카오 조회 없이 IGNORED(MEMBER_NOT_FOUND)")
  void unknownMemberIgnored() {
    Long receiptId = receive("9200000002", "UNLINK_FROM_APPS");

    processor.processDue();

    assertThat(row(receiptId)).containsEntry("status", "IGNORED").containsEntry("last_error", "MEMBER_NOT_FOUND");
    KAKAO.verify(0, anyRequestedFor(anyUrl()));
  }

  @Test
  @DisplayName("[S-B10] 카카오에서 아직 연결됨(재연결 · 늦은 웹훅)이면 INVALIDATED, 회원 · 인증 버전 그대로")
  void linkedInvalidated() {
    Member member = kakaoMember("9200000003");
    Long receiptId = receive("9200000003", "UNLINK_FROM_APPS");
    stubLinked();

    processor.processDue();

    assertThat(row(receiptId)).containsEntry("status", "INVALIDATED");
    assertMember(member.getId(), MemberStatus.ACTIVE, 0);
    assertThat(revocationCount(member.getId())).isZero();
  }

  @Test
  @DisplayName("[S-B10] 끊김(400 + -101)이면 INACTIVE + SOCIAL_UNLINKED 폐기, 기록은 INACTIVATED · 회원 · 폐기 후 버전 연결")
  void unlinkedInactivates() {
    Member member = kakaoMember("9200000004");
    Long receiptId = receive("9200000004", "UNLINK_FROM_APPS");
    stubUnlinked();

    // PENDING 단계만 (같은 주기에서 이어지는 자동 탈퇴 판단은 ② 테스트에서)
    processor.process(processor.claimNext().orElseThrow());

    assertMember(member.getId(), MemberStatus.INACTIVE, 1);
    assertThat(jdbcTemplate.queryForObject(
        "select reason from member_token_revocation where member_id = ?", String.class, member.getId()))
        .isEqualTo("SOCIAL_UNLINKED");
    Map<String, Object> receipt = row(receiptId);
    assertThat(receipt).containsEntry("status", "INACTIVATED").containsEntry("member_id", member.getId())
        .containsEntry("unlink_version", 1).containsEntry("failure_count", 0).containsEntry("attempt_no", 1);
    assertThat(receipt.get("lease_until")).isNull();
  }

  @Test
  @DisplayName("[S-B10] 같은 연결 해제가 두 번 오면 연결 해제 폐기는 한 번만, 두 기록 모두 같은 연결 해제 상태(버전 1)에 연결")
  void secondReceiptAttachesWithoutRevokingAgain() {
    Member member = kakaoMember("9200000005");
    Long first = receive("9200000005", "UNLINK_FROM_ADMIN");
    Long second = receive("9200000005", "UNLINK_FROM_ADMIN");
    stubUnlinked();

    processor.processDue();

    assertMember(member.getId(), MemberStatus.INACTIVE, 1);
    assertThat(revocationCount(member.getId())).isEqualTo(1);
    assertThat(row(first)).containsEntry("unlink_version", 1);
    assertThat(row(second)).containsEntry("unlink_version", 1);
  }

  @Test
  @DisplayName("[S-B10] 카카오 조회 실패는 10초 뒤 재시도, 5번째 실패에 FAILED(재개 단계 PENDING), 회원은 그대로")
  void kakaoUnavailableRetriesThenFails() {
    Member member = kakaoMember("9200000006");
    Long receiptId = receive("9200000006", "UNLINK_FROM_APPS");
    KAKAO.stubFor(get(urlPathEqualTo(USER_ME)).willReturn(aResponse().withStatus(500)));

    processor.processDue();
    Map<String, Object> first = row(receiptId);
    assertThat(first).containsEntry("status", "PENDING").containsEntry("failure_count", 1)
        .containsEntry("last_error", "KAKAO_UNAVAILABLE");
    Duration wait = Duration.between(Instant.now(), ((Timestamp) first.get("next_attempt_at")).toInstant());
    assertThat(wait).isBetween(Duration.ofSeconds(5), Duration.ofSeconds(11));

    for (int i = 0; i < 4; i++) {
      jdbcTemplate.update("update member_kakao_unlink_receipt set next_attempt_at = now() - interval '1 second' where id = ?", receiptId);
      processor.processDue();
    }

    assertThat(row(receiptId)).containsEntry("status", "FAILED").containsEntry("resume_state", "PENDING")
        .containsEntry("failure_count", 5);
    assertMember(member.getId(), MemberStatus.ACTIVE, 0);
  }

  @Test
  @DisplayName("[S-B10] 확보 뒤 처리권이 바뀌면(다른 작업자가 가져감) 이전 작업자는 폐기도 기록 변경도 하지 않음")
  void staleWorkerCannotApply() {
    Member member = kakaoMember("9200000007");
    Long receiptId = receive("9200000007", "UNLINK_FROM_APPS");
    stubUnlinked();

    KakaoUnlinkProcessor.Claim claim = processor.claimNext().orElseThrow();
    // 임대가 끝나 다른 작업자가 다시 확보한 상황
    jdbcTemplate.update("update member_kakao_unlink_receipt set attempt_no = attempt_no + 1 where id = ?", receiptId);
    processor.process(claim);

    assertMember(member.getId(), MemberStatus.ACTIVE, 0);
    assertThat(revocationCount(member.getId())).isZero();
    assertThat(row(receiptId)).containsEntry("status", "PENDING").containsEntry("attempt_no", 2);
  }

  // ===== ② INACTIVATED =====

  @Test
  @DisplayName("[S-B10] 관리자 해제(UNLINK_FROM_ADMIN)는 INACTIVE까지만: 기록 DONE, 3곳 확인 · 탈퇴 없음")
  void adminUnlinkStopsAtInactive() {
    Member member = kakaoMember("9200000011");
    Long receiptId = receive("9200000011", "UNLINK_FROM_ADMIN");
    stubUnlinked();

    assertThat(processor.processDue()).isEqualTo(2); // PENDING → INACTIVATED → DONE

    assertThat(row(receiptId)).containsEntry("status", "DONE").containsEntry("handles_withdrawal", false);
    assertMember(member.getId(), MemberStatus.INACTIVE, 1);
    KAKAO.verify(0, anyRequestedFor(urlPathMatching("/internal/.*")));
  }

  @Test
  @DisplayName("[S-B10] 자동 탈퇴 사유 + 3곳 ALLOWED면 탈퇴(익명화 · MemberWithdrawn), 기록은 자동 탈퇴 처리 기록으로 DONE")
  void autoWithdrawalAllowed() {
    Member member = kakaoMember("9200000012");
    Long receiptId = receive("9200000012", "UNLINK_FROM_APPS");
    stubUnlinked();
    stubChecks(PASS);

    processor.processDue();

    Member after = memberRepository.findById(member.getId()).orElseThrow();
    assertThat(after.getStatus()).isEqualTo(MemberStatus.DELETED);
    assertThat(after.getProviderId()).isNull();
    assertThat(after.getEmail()).isNull();
    assertThat(after.getTokenVersion()).isEqualTo(2); // 연결 해제 폐기 + 탈퇴 폐기
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from outbox_event where event_type = 'MemberWithdrawn' and aggregate_id = ?",
        Long.class, String.valueOf(member.getId()))).isEqualTo(1);
    assertThat(row(receiptId)).containsEntry("status", "DONE").containsEntry("handles_withdrawal", true);
  }

  @Test
  @DisplayName("[S-B10] 자동 탈퇴 사유 + 3곳 BLOCKED면 INACTIVE 그대로 안내 대기(NOTICE_PENDING)")
  void autoWithdrawalBlocked() {
    Member member = kakaoMember("9200000013");
    Long receiptId = receive("9200000013", "ACCOUNT_DELETE");
    stubUnlinked();
    stubChecks(BLOCK);

    processor.processDue();

    assertMember(member.getId(), MemberStatus.INACTIVE, 1);
    assertThat(row(receiptId)).containsEntry("status", "NOTICE_PENDING").containsEntry("handles_withdrawal", true)
        .containsEntry("failure_count", 0);
  }

  @Test
  @DisplayName("[S-B10] 3곳 확인 실패(UNAVAILABLE)는 안내 없이 재시도하고, 다음 시도에서 같은 처리 기록이 이어서 탈퇴")
  void unavailableThenSelfContinues() {
    Member member = kakaoMember("9200000014");
    Long receiptId = receive("9200000014", "FORCED_ACCOUNT_DELETE");
    stubUnlinked();
    stubChecks(null);

    processor.processDue();
    assertThat(row(receiptId)).containsEntry("status", "INACTIVATED").containsEntry("handles_withdrawal", true)
        .containsEntry("failure_count", 1).containsEntry("last_error", "WITHDRAWAL_CHECK_UNAVAILABLE");

    stubChecks(PASS);
    makeDue(receiptId);
    processor.processDue();

    assertThat(memberRepository.findById(member.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.DELETED);
    assertThat(row(receiptId)).containsEntry("status", "DONE");
  }

  @Test
  @DisplayName("[S-B10] 관리자 해제로 INACTIVE가 된 뒤 자동 탈퇴 사유가 오면 추가 폐기 없이 그 기록이 자동 탈퇴를 진행")
  void adminThenAutoWithdrawal() {
    Member member = kakaoMember("9200000015");
    Long admin = receive("9200000015", "UNLINK_FROM_ADMIN");
    Long apps = receive("9200000015", "UNLINK_FROM_APPS");
    stubUnlinked();
    stubChecks(BLOCK);

    processor.processDue();

    assertThat(revocationCount(member.getId())).isEqualTo(1); // 연결 해제 폐기 한 번
    assertThat(row(admin)).containsEntry("status", "DONE").containsEntry("handles_withdrawal", false);
    assertThat(row(apps)).containsEntry("status", "NOTICE_PENDING").containsEntry("handles_withdrawal", true)
        .containsEntry("unlink_version", 1);
  }

  @Test
  @DisplayName("[S-B10] 같은 연결 해제 상태에 자동 탈퇴 사유가 두 번 오면 처리 기록은 하나, 나머지는 DUPLICATE")
  void duplicateAutoWithdrawal() {
    kakaoMember("9200000016");
    Long first = receive("9200000016", "UNLINK_FROM_APPS");
    Long second = receive("9200000016", "UNLINK_FROM_APPS");
    stubUnlinked();
    stubChecks(BLOCK);

    processor.processDue();

    assertThat(row(first)).containsEntry("status", "NOTICE_PENDING").containsEntry("handles_withdrawal", true);
    assertThat(row(second)).containsEntry("status", "DUPLICATE").containsEntry("handles_withdrawal", false);
    assertThat(jdbcTemplate.queryForObject("select count(*) from member_kakao_unlink_receipt where handles_withdrawal",
        Long.class)).isEqualTo(1);
  }

  @Test
  @DisplayName("[S-B11] 자동 탈퇴 판단 전에 회원이 ACTIVE로 돌아오면(재연결) 기록은 INVALIDATED, 탈퇴하지 않음")
  void restoredMemberIsNotWithdrawn() {
    Member member = kakaoMember("9200000017");
    Long receiptId = receive("9200000017", "UNLINK_FROM_APPS");
    stubUnlinked();
    stubChecks(null);
    processor.processDue(); // INACTIVE, 확인 실패로 INACTIVATED에서 대기

    jdbcTemplate.update("update member set status = 'ACTIVE' where id = ?", member.getId());
    stubChecks(PASS);
    makeDue(receiptId);
    processor.processDue();

    assertMember(member.getId(), MemberStatus.ACTIVE, 1);
    assertThat(row(receiptId)).containsEntry("status", "INVALIDATED");
  }

  private void stubChecks(String body) {
    for (String path : new String[]{"orders", "payments", "settlements"}) {
      KAKAO.stubFor(get(urlPathMatching("/internal/v1/" + path + "/members/\\d+/withdrawalCheck"))
          .willReturn(body == null
              ? aResponse().withStatus(500)
              : aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                  .withBody("{\"status\": 200, \"code\": \"OK\", \"message\": \"ok\", \"data\": " + body + "}")));
    }
  }

  private void makeDue(Long receiptId) {
    jdbcTemplate.update("update member_kakao_unlink_receipt set next_attempt_at = now() - interval '1 second' where id = ?", receiptId);
  }

  private Long receive(String providerId, String referrerType) {
    return receiptRepository.save(
        KakaoUnlinkReceipt.received(providerId, referrerType, Instant.now().minusSeconds(60))).getId();
  }

  private Member kakaoMember(String providerId) {
    int n = SEQ.incrementAndGet();
    Member member = memberRepository.save(Member.createSocial(Provider.KAKAO, providerId,
        "unlinkproc" + n + "@test.com", "처리" + n, String.format("019%08d", 60_000_000 + n),
        "004", "v1:test-account", null, "홍길동"));
    memberIds.add(member.getId());
    return member;
  }

  private void stubLinked() {
    KAKAO.stubFor(get(urlPathEqualTo(USER_ME)).willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json").withBody("{\"id\":1}")));
  }

  private void stubUnlinked() {
    KAKAO.stubFor(get(urlPathEqualTo(USER_ME)).willReturn(aResponse().withStatus(400)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"msg\":\"NotRegisteredUserException\",\"code\":-101}")));
  }

  private void assertMember(Long memberId, MemberStatus status, int tokenVersion) {
    Member member = memberRepository.findById(memberId).orElseThrow();
    assertThat(member.getStatus()).isEqualTo(status);
    assertThat(member.getTokenVersion()).isEqualTo(tokenVersion);
  }

  private long revocationCount(Long memberId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from member_token_revocation where member_id = ?", Long.class, memberId);
  }

  private Map<String, Object> row(Long receiptId) {
    return jdbcTemplate.queryForMap("select * from member_kakao_unlink_receipt where id = ?", receiptId);
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

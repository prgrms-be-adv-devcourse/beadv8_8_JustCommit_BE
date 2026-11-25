package com.justcommit.backend.member.unlink.application;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.common.testcontainers.SharedRedis;
import com.justcommit.backend.member.auth.application.SocialLoginCommand;
import com.justcommit.backend.member.auth.application.SocialLoginResult;
import com.justcommit.backend.member.auth.application.SocialLoginService;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.unlink.domain.KakaoUnlinkReceipt;
import com.justcommit.backend.member.unlink.infrastructure.mail.KakaoUnlinkNoticeMailSender;
import com.justcommit.backend.member.unlink.infrastructure.repository.KakaoUnlinkReceiptRepository;
import com.justcommit.backend.member.withdrawal.application.WithdrawalCheckService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 카카오 연결 해제 뒤 탈퇴가 보류됐던 회원의 재로그인 안내 (#616).
 * 실제 처리기(웹훅 기록 → INACTIVE → 3곳 확인 → 안내 메일)로 상태를 만든 뒤 카카오로 다시 로그인해,
 * 탈퇴가 보류로 정해진 연결 해제 상태에서 복구될 때만 안내 표시가 켜지는지 확인한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class KakaoReloginWithdrawalHeldIntegrationTest {

  @RegisterExtension
  static final WireMockExtension KAKAO = WireMockExtension.newInstance()
      .options(wireMockConfig().dynamicPort())
      .build();

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final AtomicInteger SEQ = new AtomicInteger();
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
  // 재로그인이 처리 기록을 읽은 "뒤" · 무효화하기 "전"에 다른 일을 끼워 넣기 위해 가로챔. 평소에는 실제 빈 그대로
  @MockitoSpyBean KakaoUnlinkReceiptRepository receiptRepository;
  // 3곳 확인 도중(처리 기록 지정 뒤 · BLOCKED 저장 전)에 처리기를 멈추기 위해 가로챔
  @MockitoSpyBean WithdrawalCheckService withdrawalCheckService;
  @Autowired SocialLoginService socialLoginService;
  @Autowired MemberRepository memberRepository;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired StringRedisTemplate redisTemplate;

  // 실제 SMTP 대신 가짜 (보낸 횟수만 확인)
  @MockitoBean KakaoUnlinkNoticeMailSender noticeMailSender;

  private final List<Long> memberIds = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from member_kakao_unlink_receipt");
    for (Long id : memberIds) {
      jdbcTemplate.update("delete from member_token_revocation where member_id = ?", id);
      jdbcTemplate.update("delete from member where id = ?", id);
    }
    try (var connection = redisTemplate.getRequiredConnectionFactory().getConnection()) {
      connection.serverCommands().flushAll();
    }
  }

  @Test
  @DisplayName("[#616] 보류 판정 전(INACTIVATED)에 다시 로그인하면 복구만 하고 안내 표시 없음, 뒤늦은 처리도 메일을 보내지 않음")
  void reloginBeforeDecision_noNotice() {
    Member member = kakaoMember("9400000001");
    Long receiptId = receive("9400000001", "ACCOUNT_DELETE");
    stubUnlinked();
    stubChecks(BLOCK);
    processor.process(processor.claimNext().orElseThrow()); // PENDING → INACTIVATED (3곳 확인 전)
    assertThat(status(receiptId)).isEqualTo("INACTIVATED");

    boolean held = login("9400000001");

    assertThat(held).isFalse();
    assertMember(member, MemberStatus.ACTIVE);
    assertThat(status(receiptId)).isEqualTo("INVALIDATED");
    processor.processDue();
    verify(noticeMailSender, never()).send(any());
  }

  @Test
  @DisplayName("[#616] 탈퇴 보류로 안내 메일까지 보낸(DONE) 뒤 다시 로그인하면 안내 표시, 메일은 한 번 그대로")
  void reloginAfterNoticeMail_notice() {
    Member member = kakaoMember("9400000002");
    Long receiptId = receive("9400000002", "ACCOUNT_DELETE");
    stubUnlinked();
    stubChecks(BLOCK);
    processor.processDue(); // INACTIVE → BLOCKED → 안내 메일 → DONE
    assertThat(status(receiptId)).isEqualTo("DONE");
    verify(noticeMailSender, times(1)).send(member.getEmail());

    boolean held = login("9400000002");

    assertThat(held).isTrue();
    assertMember(member, MemberStatus.ACTIVE);
    assertThat(status(receiptId)).isEqualTo("DONE"); // 끝난 기록은 무효화하지 않음 (기존 동작)
    verify(noticeMailSender, times(1)).send(any());
  }

  @Test
  @DisplayName("[#616] 탈퇴 보류로 정해졌지만 안내 메일 전(NOTICE_PENDING)에 다시 로그인하면 안내 표시, 대기 중인 메일은 취소")
  void reloginBeforeNoticeMail_notice() {
    Member member = kakaoMember("9400000003");
    Long receiptId = receive("9400000003", "ACCOUNT_DELETE");
    stubUnlinked();
    stubChecks(BLOCK);
    processor.process(processor.claimNext().orElseThrow()); // PENDING → INACTIVATED
    processor.process(processor.claimNext().orElseThrow()); // INACTIVATED → BLOCKED → NOTICE_PENDING
    assertThat(status(receiptId)).isEqualTo("NOTICE_PENDING");
    verify(noticeMailSender, never()).send(any());

    boolean held = login("9400000003");

    assertThat(held).isTrue();
    assertMember(member, MemberStatus.ACTIVE);
    assertThat(status(receiptId)).isEqualTo("INVALIDATED");
    processor.processDue();
    verify(noticeMailSender, never()).send(any());
  }

  @Test
  @DisplayName("[#616] 관리자 해제로 INACTIVE가 된 회원은 다시 로그인해도 안내 표시 없음 (탈퇴 판단 대상이 아님)")
  void reloginAfterAdminUnlink_noNotice() {
    Member member = kakaoMember("9400000004");
    Long receiptId = receive("9400000004", "UNLINK_FROM_ADMIN");
    stubUnlinked();
    processor.processDue(); // INACTIVE까지만 → DONE
    assertThat(status(receiptId)).isEqualTo("DONE");
    assertMember(member, MemberStatus.INACTIVE);

    boolean held = login("9400000004");

    assertThat(held).isFalse();
    assertMember(member, MemberStatus.ACTIVE);
    verify(noticeMailSender, never()).send(any());
  }

  @Test
  @DisplayName("[#616] 평소(ACTIVE) 카카오 로그인 · 복구 뒤 다시 로그인 · 이전 연결 해제의 보류 기록은 안내 표시 없음")
  void otherLogins_noNotice() {
    Member member = kakaoMember("9400000005");
    assertThat(login("9400000005")).isFalse(); // 평소 로그인

    receive("9400000005", "ACCOUNT_DELETE");
    stubUnlinked();
    stubChecks(BLOCK);
    processor.processDue();
    assertThat(login("9400000005")).isTrue(); // 보류에서 복구: 한 번만
    assertThat(login("9400000005")).isFalse(); // 이미 ACTIVE

    // 다음 연결 해제는 관리자 해제: 이전 연결 해제 상태의 보류 기록(DONE)은 보지 않는다
    receive("9400000005", "UNLINK_FROM_ADMIN");
    processor.processDue();
    assertMember(member, MemberStatus.INACTIVE);
    assertThat(login("9400000005")).isFalse();
  }

  @Test
  @DisplayName("[#616] 재로그인이 처리 기록을 잠그고 읽으면, 무효화하기 전에 처리기가 BLOCKED(NOTICE_PENDING)를 저장하지 못하고 보류 판정 전 재로그인과 같은 결과")
  void blockedSavedBetweenReadAndInvalidate_noticeNotLost() throws Exception {
    Member member = kakaoMember("9400000006");
    Long receiptId = receive("9400000006", "ACCOUNT_DELETE");
    stubUnlinked();
    stubChecks(BLOCK);
    processor.process(processor.claimNext().orElseThrow()); // PENDING → INACTIVATED
    KakaoUnlinkProcessor.Claim decide = processor.claimNext().orElseThrow();

    // 처리기: 처리 기록 지정(회원 잠금 풀림) → 3곳 확인에서 재로그인이 기록을 읽을 때까지 멈춤 → BLOCKED 저장
    CountDownLatch checkEntered = new CountDownLatch(1);
    CountDownLatch loginRead = new CountDownLatch(1);
    doAnswer(invocation -> {
      checkEntered.countDown();
      loginRead.await(5, TimeUnit.SECONDS);
      return invocation.callRealMethod();
    }).when(withdrawalCheckService).check(member.getId());
    Thread worker = new Thread(() -> processor.process(decide));
    worker.start();
    assertThat(checkEntered.await(5, TimeUnit.SECONDS)).isTrue();

    // 재로그인: 처리 기록을 읽은 뒤 무효화하기 직전에 처리기를 보내고, 처리기가 끝날 수 있으면(최대 2초) 기다림
    AtomicReference<String> statusBeforeInvalidate = new AtomicReference<>();
    doAnswer(invocation -> {
      loginRead.countDown();
      worker.join(2000);
      statusBeforeInvalidate.set(status(receiptId));
      // 저장소는 인터페이스 프록시라 callRealMethod 대신 spy의 기본 응답(실제 빈으로 위임)을 그대로 씀
      return mockingDetails(receiptRepository).getMockCreationSettings().getDefaultAnswer().answer(invocation);
    }).when(receiptRepository).invalidatePending(anyString(), any());

    boolean held = login("9400000006");
    worker.join(10_000);

    // 잠금이 없으면 이 사이에 NOTICE_PENDING이 저장돼, 안내 없이 보류가 무효화되고 대기 메일만 취소됐다
    assertThat(statusBeforeInvalidate.get())
        .as("재로그인이 끝날 때까지 처리기는 BLOCKED를 저장하지 못함")
        .isEqualTo("INACTIVATED");
    assertThat(held).isFalse();
    assertThat(worker.isAlive()).isFalse();
    assertMember(member, MemberStatus.ACTIVE);
    assertThat(status(receiptId)).isEqualTo("INVALIDATED");
    processor.processDue();
    verify(noticeMailSender, never()).send(any());
  }

  @Test
  @DisplayName("[#616] 처리기가 BLOCKED를 저장하려고 처리 기록을 먼저 잠갔으면, 재로그인은 저장이 끝난 NOTICE_PENDING을 읽고 안내 표시")
  void blockedLockedBeforeRelogin_notice() throws Exception {
    Member member = kakaoMember("9400000008");
    Long receiptId = receive("9400000008", "ACCOUNT_DELETE");
    stubUnlinked();
    stubChecks(BLOCK);
    processor.process(processor.claimNext().orElseThrow()); // PENDING → INACTIVATED
    KakaoUnlinkProcessor.Claim decide = processor.claimNext().orElseThrow();

    // 처리기: 3곳 확인(BLOCKED) 뒤 결과를 반영하려고 기록을 잠근 상태에서 잠시 멈춤 (그동안 재로그인이 같은 기록을 잠그려 함)
    CountDownLatch checked = new CountDownLatch(1);
    CountDownLatch locked = new CountDownLatch(1);
    doAnswer(invocation -> {
      Object result = invocation.callRealMethod();
      checked.countDown();
      return result;
    }).when(withdrawalCheckService).check(member.getId());
    doAnswer(invocation -> {
      Object receipt = mockingDetails(receiptRepository).getMockCreationSettings().getDefaultAnswer().answer(invocation);
      if (checked.getCount() == 0) {
        locked.countDown();
        Thread.sleep(1000);
      }
      return receipt;
    }).when(receiptRepository).findByIdForUpdate(decide.id());
    Thread worker = new Thread(() -> processor.process(decide));
    worker.start();
    assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

    boolean held = login("9400000008");
    worker.join(10_000);

    assertThat(held).isTrue();
    assertThat(worker.isAlive()).isFalse();
    assertMember(member, MemberStatus.ACTIVE);
    assertThat(status(receiptId)).isEqualTo("INVALIDATED");
    processor.processDue();
    verify(noticeMailSender, never()).send(any());
  }

  @Test
  @DisplayName("[#616] 안내 메일을 보내는 도중 다시 로그인하면 안내 표시, 이미 시작된 메일은 그대로 한 번 나가고 끝난 처리는 무효를 덮어쓰지 않음 (기존 동작)")
  void reloginWhileMailSending_noticeAndMailUnchanged() throws Exception {
    Member member = kakaoMember("9400000007");
    Long receiptId = receive("9400000007", "ACCOUNT_DELETE");
    stubUnlinked();
    stubChecks(BLOCK);
    processor.process(processor.claimNext().orElseThrow()); // PENDING → INACTIVATED
    processor.process(processor.claimNext().orElseThrow()); // INACTIVATED → NOTICE_PENDING
    KakaoUnlinkProcessor.Claim notice = processor.claimNext().orElseThrow();

    CountDownLatch mailStarted = new CountDownLatch(1);
    CountDownLatch loginDone = new CountDownLatch(1);
    doAnswer(invocation -> {
      mailStarted.countDown();
      loginDone.await(5, TimeUnit.SECONDS);
      return null;
    }).when(noticeMailSender).send(member.getEmail());
    Thread worker = new Thread(() -> processor.process(notice));
    worker.start();
    assertThat(mailStarted.await(5, TimeUnit.SECONDS)).isTrue();

    boolean held = login("9400000007");
    loginDone.countDown();
    worker.join(10_000);

    assertThat(held).isTrue();
    assertThat(worker.isAlive()).isFalse();
    assertMember(member, MemberStatus.ACTIVE);
    assertThat(status(receiptId)).isEqualTo("INVALIDATED");
    verify(noticeMailSender, times(1)).send(member.getEmail());
  }

  private boolean login(String providerId) {
    SocialLoginResult result = socialLoginService.login(
        new SocialLoginCommand(Provider.KAKAO, providerId, providerId + "@kakao.com"));
    assertThat(result).isInstanceOf(SocialLoginResult.ExistingMember.class);
    return ((SocialLoginResult.ExistingMember) result).withdrawalHeldRestored();
  }

  private void stubChecks(String body) {
    for (String path : new String[]{"orders", "payments", "settlements"}) {
      KAKAO.stubFor(get(urlPathMatching("/internal/v1/" + path + "/members/\\d+/withdrawalCheck"))
          .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
              .withBody("{\"status\": 200, \"code\": \"OK\", \"message\": \"ok\", \"data\": " + body + "}")));
    }
  }

  private void stubUnlinked() {
    KAKAO.stubFor(get(urlPathEqualTo("/v2/user/me")).willReturn(aResponse().withStatus(400)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"msg\":\"NotRegisteredUserException\",\"code\":-101}")));
  }

  private Long receive(String providerId, String referrerType) {
    return receiptRepository.save(
        KakaoUnlinkReceipt.received(providerId, referrerType, Instant.now().minusSeconds(60))).getId();
  }

  private String status(Long receiptId) {
    return jdbcTemplate.queryForObject("select status from member_kakao_unlink_receipt where id = ?",
        String.class, receiptId);
  }

  private void assertMember(Member member, MemberStatus status) {
    assertThat(memberRepository.findById(member.getId()).orElseThrow().getStatus()).isEqualTo(status);
  }

  private Member kakaoMember(String providerId) {
    int n = SEQ.incrementAndGet();
    Member member = memberRepository.save(Member.createSocial(Provider.KAKAO, providerId,
        "held" + n + "@test.com", "보류" + n, String.format("019%08d", 80_000_000 + n),
        "004", "v1:test-account", null, "홍길동"));
    memberIds.add(member.getId());
    return member;
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

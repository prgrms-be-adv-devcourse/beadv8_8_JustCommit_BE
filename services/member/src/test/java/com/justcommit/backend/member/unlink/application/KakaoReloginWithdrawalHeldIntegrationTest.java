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
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
  @Autowired KakaoUnlinkReceiptRepository receiptRepository;
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

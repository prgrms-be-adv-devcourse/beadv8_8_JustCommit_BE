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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S-B11] 카카오 재로그인 (#422). 정상 카카오 로그인은 회원 잠금 안에서
 * 이전 미처리 수신 기록을 항상(ACTIVE여도) 무효로 하고, INACTIVE면 ACTIVE로 복구한 뒤 발급한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class KakaoReloginIntegrationTest {

  @RegisterExtension
  static final WireMockExtension KAKAO = WireMockExtension.newInstance()
      .options(wireMockConfig().dynamicPort())
      .build();

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final AtomicInteger SEQ = new AtomicInteger();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    SharedPostgres.register(registry);
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
    SharedRedis.register(registry);
    registry.add("member.kakao.api-base-url", KAKAO::baseUrl);
  }

  @Autowired SocialLoginService socialLoginService;
  @Autowired KakaoUnlinkProcessor processor;
  @Autowired KakaoUnlinkReceiptRepository receiptRepository;
  @Autowired MemberRepository memberRepository;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired StringRedisTemplate redisTemplate;

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
  @DisplayName("[S-B11] INACTIVE 회원(관리자 해제 포함)이 카카오로 다시 로그인하면 ACTIVE로 복구 · 토큰 발급, 미처리 기록은 모두 무효(끝난 기록은 그대로)")
  void reloginRestoresInactiveMember() {
    Member member = kakaoMember("9300000001");
    jdbcTemplate.update("update member set status = 'INACTIVE', token_version = 1 where id = ?", member.getId());
    Long pending = receipt("9300000001", "UNLINK_FROM_APPS", "PENDING", null, null);
    Long inactivated = receipt("9300000001", "UNLINK_FROM_ADMIN", "INACTIVATED", member.getId(), null);
    Long notice = receipt("9300000001", "UNLINK_FROM_APPS", "NOTICE_PENDING", member.getId(), null);
    Long failed = receipt("9300000001", "UNLINK_FROM_APPS", "FAILED", member.getId(), "INACTIVATED");
    Long done = receipt("9300000001", "UNLINK_FROM_ADMIN", "DONE", member.getId(), null);

    SocialLoginResult result = socialLoginService.login(new SocialLoginCommand(Provider.KAKAO, "9300000001", "a@kakao.com"));

    assertThat(result).isInstanceOf(SocialLoginResult.ExistingMember.class);
    assertThat(memberRepository.findById(member.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
    for (Long id : List.of(pending, inactivated, notice, failed)) {
      assertThat(status(id)).as("receipt %d", id).isEqualTo("INVALIDATED");
    }
    assertThat(jdbcTemplate.queryForObject("select resume_state from member_kakao_unlink_receipt where id = ?",
        String.class, failed)).isNull();
    assertThat(status(done)).isEqualTo("DONE");
  }

  @Test
  @DisplayName("[S-B11] ACTIVE 회원이 로그인해도 미처리 기록은 무효: 작업자가 끊김을 확인한 직후 재로그인하면 옛 조회 결과로 INACTIVE가 되지 않음")
  void reloginInvalidatesEvenWhenActive() {
    Member member = kakaoMember("9300000002");
    receiptRepository.save(KakaoUnlinkReceipt.received("9300000002", "UNLINK_FROM_APPS", Instant.now().minusSeconds(60)));
    // 작업자가 기록을 확보하고 카카오에서 "끊김"을 받은 상태
    KakaoUnlinkProcessor.Claim claim = processor.claimNext().orElseThrow();
    KAKAO.stubFor(get(urlPathEqualTo("/v2/user/me")).willReturn(aResponse().withStatus(400)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"msg\":\"NotRegisteredUserException\",\"code\":-101}")));

    // 그 사이 사용자가 다시 카카오로 로그인
    socialLoginService.login(new SocialLoginCommand(Provider.KAKAO, "9300000002", "b@kakao.com"));
    processor.process(claim);

    Member after = memberRepository.findById(member.getId()).orElseThrow();
    assertThat(after.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    assertThat(after.getTokenVersion()).isZero();
    assertThat(jdbcTemplate.queryForObject("select count(*) from member_token_revocation where member_id = ?",
        Long.class, member.getId())).isZero();
    assertThat(status(claim.id())).isEqualTo("INVALIDATED");
  }

  @Test
  @DisplayName("[S-B11] 다른 회원번호의 기록은 건드리지 않음")
  void otherProviderIdUntouched() {
    kakaoMember("9300000003");
    Long other = receipt("9300000099", "UNLINK_FROM_APPS", "PENDING", null, null);

    socialLoginService.login(new SocialLoginCommand(Provider.KAKAO, "9300000003", "c@kakao.com"));

    assertThat(status(other)).isEqualTo("PENDING");
  }

  private Long receipt(String providerId, String referrerType, String status, Long memberId, String resumeState) {
    jdbcTemplate.update("""
        insert into member_kakao_unlink_receipt
            (provider_id, referrer_type, received_at, status, resume_state, member_id, unlink_version,
             next_attempt_at, created_at, updated_at)
        values (?, ?, now(), ?, ?, ?, ?, now() + interval '1 hour', now(), now())
        """, providerId, referrerType, status, resumeState, memberId, memberId == null ? null : 1);
    return jdbcTemplate.queryForObject("select max(id) from member_kakao_unlink_receipt", Long.class);
  }

  private String status(Long receiptId) {
    return jdbcTemplate.queryForObject("select status from member_kakao_unlink_receipt where id = ?",
        String.class, receiptId);
  }

  private Member kakaoMember(String providerId) {
    int n = SEQ.incrementAndGet();
    Member member = memberRepository.save(Member.createSocial(Provider.KAKAO, providerId,
        "relogin" + n + "@test.com", "재로" + n, String.format("019%08d", 70_000_000 + n),
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

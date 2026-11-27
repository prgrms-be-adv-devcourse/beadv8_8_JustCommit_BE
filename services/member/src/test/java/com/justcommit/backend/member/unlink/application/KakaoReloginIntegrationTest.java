package com.justcommit.backend.member.unlink.application;

import com.justcommit.backend.member.support.MemberIntegrationTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.security.JwtVerifier;
import com.justcommit.backend.member.auth.application.SocialLoginCommand;
import com.justcommit.backend.member.auth.application.SocialLoginResult;
import com.justcommit.backend.member.auth.application.SocialLoginService;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.unlink.domain.KakaoUnlinkReceipt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

/**
 * [S-B11] 카카오 재로그인 (#422). 정상 카카오 로그인은 회원 잠금 안에서
 * 이전 미처리 수신 기록을 항상(ACTIVE여도) 무효로 하고, INACTIVE면 ACTIVE로 복구한 뒤 발급한다.
 */
class KakaoReloginIntegrationTest extends MemberIntegrationTest {

  // 베이스의 공유 WireMock (테스트마다 응답 설정 · 요청 기록 초기화)
  private static final WireMockServer KAKAO = EXTERNAL_API;

  private static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired SocialLoginService socialLoginService;
  // Gateway와 같은 검증기로 iat(초)를 꺼냄
  @Autowired JwtVerifier jwtVerifier;
  @Autowired KakaoUnlinkProcessor processor;
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
  @DisplayName("[S-B11] 작업자가 카카오에서 끊김 응답을 받은 직후 재로그인하면(ACTIVE여도 미처리 기록 무효), 옛 조회 결과로 INACTIVE가 되지 않음")
  void reloginInvalidatesEvenWhenActive() {
    Member member = kakaoMember("9300000002");
    receiptRepository.save(KakaoUnlinkReceipt.received("9300000002", "UNLINK_FROM_APPS", Instant.now().minusSeconds(60)));
    KAKAO.stubFor(get(urlPathEqualTo("/v2/user/me")).willReturn(aResponse().withStatus(400)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"msg\":\"NotRegisteredUserException\",\"code\":-101}")));
    // 작업자가 "끊김" 응답을 받은 직후(폐기하러 회원을 잠그기 전)에 사용자가 다시 카카오로 로그인
    doAnswer(invocation -> {
      Object linkStatus = invocation.callRealMethod();
      socialLoginService.login(new SocialLoginCommand(Provider.KAKAO, "9300000002", "b@kakao.com"));
      return linkStatus;
    }).when(kakaoAdminClient).linkStatus("9300000002");

    KakaoUnlinkProcessor.Claim claim = processor.claimNext().orElseThrow();
    processor.process(claim);

    verify(kakaoAdminClient).linkStatus("9300000002"); // 경쟁 경로를 실제로 지났는지

    Member after = memberRepository.findById(member.getId()).orElseThrow();
    assertThat(after.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    assertThat(after.getTokenVersion()).isZero();
    assertThat(jdbcTemplate.queryForObject("select count(*) from member_token_revocation where member_id = ?",
        Long.class, member.getId())).isZero();
    assertThat(status(claim.id())).isEqualTo("INVALIDATED");
  }

  @Test
  @DisplayName("[S-B11 · #546] 웹훅 폐기와 같은 초에 재연결하면 다음 초까지 기다린 뒤 발급해, 새 Access의 iat가 끊은 시각보다 뒤다 (Gateway 규칙상 거절 대상 아님)")
  void sameSecondReconnectIssuesAfterCutoff() {
    Member member = kakaoMember("9300000004");
    receiptRepository.save(KakaoUnlinkReceipt.received("9300000004", "UNLINK_FROM_APPS", Instant.now().minusSeconds(60)));
    KAKAO.stubFor(get(urlPathEqualTo("/v2/user/me")).willReturn(aResponse().withStatus(400)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"msg\":\"NotRegisteredUserException\",\"code\":-101}")));
    // 실제 웹훅 처리로 INACTIVE + SOCIAL_UNLINKED 폐기 → Redis에 끊은 시각(초) 기록
    processor.process(processor.claimNext().orElseThrow());
    // Gateway TokenRevocationChecker와 같은 방식으로 값을 읽음
    long cutoff = Long.parseLong(redisTemplate.opsForValue().get("auth:revoked-member:" + member.getId()));

    // 첫 시각은 끊은 시각과 같은 초(0.5초 뒤), 그 뒤로는 다음 초로 고정 (실제 시각에 따라 결과가 달라지지 않게)
    doReturn(Instant.ofEpochSecond(cutoff).plusMillis(500), Instant.ofEpochSecond(cutoff + 1))
        .when(databaseClock).now();
    SocialLoginResult result = socialLoginService.login(new SocialLoginCommand(Provider.KAKAO, "9300000004", "d@kakao.com"));

    // 재연결 성공 (ACTIVE 복구 · 토큰 발급)
    assertThat(memberRepository.findById(member.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
    String accessToken = ((SocialLoginResult.ExistingMember) result).loginResult().accessToken();
    // 다음 초에 발급됐으므로 Gateway 규칙(iat <= 끊은 시각)에 걸리지 않는다
    assertThat(jwtVerifier.verifyAccessToken(accessToken).issuedAt()).isGreaterThan(cutoff);
  }

  @Test
  @DisplayName("[S-B11 · #546] 재연결이 발급 시각을 기다리다 MEMBER_BUSY로 끝나면 회원은 INACTIVE 그대로이고 연결 해제 기록도 바뀌지 않는다")
  void reconnectMemberBusy_keepsInactiveAndReceipt() {
    Member member = kakaoMember("9300000005");
    receiptRepository.save(KakaoUnlinkReceipt.received("9300000005", "UNLINK_FROM_APPS", Instant.now().minusSeconds(60)));
    KAKAO.stubFor(get(urlPathEqualTo("/v2/user/me")).willReturn(aResponse().withStatus(400)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"msg\":\"NotRegisteredUserException\",\"code\":-101}")));
    // 실제 웹훅 처리로 INACTIVE + SOCIAL_UNLINKED 폐기
    KakaoUnlinkProcessor.Claim claim = processor.claimNext().orElseThrow();
    processor.process(claim);
    long cutoff = Long.parseLong(redisTemplate.opsForValue().get("auth:revoked-member:" + member.getId()));
    String receiptStatus = status(claim.id());

    // 시각이 계속 끊은 시각과 같은 초 → 두 번 시도한 뒤 MEMBER_BUSY
    doReturn(Instant.ofEpochSecond(cutoff).plusMillis(500)).when(databaseClock).now();

    assertThatThrownBy(() -> socialLoginService.login(new SocialLoginCommand(Provider.KAKAO, "9300000005", "e@kakao.com")))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(MemberErrorCode.MEMBER_BUSY);

    // 발급하지 못한 시도에서는 복구 · 무효화가 남지 않는다
    assertThat(memberRepository.findById(member.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.INACTIVE);
    assertThat(status(claim.id())).isEqualTo(receiptStatus);
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
}

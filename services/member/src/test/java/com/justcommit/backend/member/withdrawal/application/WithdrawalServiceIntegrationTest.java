package com.justcommit.backend.member.withdrawal.application;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import com.justcommit.backend.common.exception.ErrorCode;
import com.justcommit.backend.member.address.domain.Address;
import com.justcommit.backend.member.address.infrastructure.AddressRepository;
import com.justcommit.backend.member.auth.domain.VerificationBinding;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.domain.exception.PasswordChangeLockedException;
import com.justcommit.backend.member.infrastructure.crypto.MemberHmac;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import com.justcommit.backend.member.seller.domain.Seller;
import com.justcommit.backend.member.seller.domain.SellerReviewConsentPolicy;
import com.justcommit.backend.member.seller.infrastructure.SellerRepository;
import com.justcommit.backend.member.withdrawal.infrastructure.redis.WithdrawalTokenStore;
import com.justcommit.backend.member.withdrawal.infrastructure.repository.MemberWithdrawalRecordRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.common.testcontainers.SharedRedis;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

/**
 * 직접 탈퇴 (#407)를 실제 PostgreSQL · Redis로 확인한다. market · payment · settlement · 카카오는 WireMock 하나로 대신한다.
 * - 이 클래스에는 @Transactional이 없어, 서비스 호출 뒤의 조회는 DB에서 다시 읽은 값이다
 * - 재확인 트랜잭션이 외부 호출 전에 끝나는지는 따로 확인하지 않는다: 끝나지 않았다면 revoke()가 활성 트랜잭션을 거절해
 *   성공 테스트가 실패한다
 * - MemberWithdrawn Outbox를 포함한 원자성은 #408에서 확인한다 (지금은 회원 · 배송지 · 소개글 · 탈퇴 기록 · 폐기 작업)
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class WithdrawalServiceIntegrationTest {

  @RegisterExtension
  static final WireMockExtension EXTERNAL = WireMockExtension.newInstance()
      .options(wireMockConfig().dynamicPort())
      .build();

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final AtomicInteger SEQ = new AtomicInteger();
  private static final String PASSWORD = "plant1234";
  private static final String UNLINK = "/v1/user/unlink";
  private static final String[] CHECK_PATHS = {
      "/internal/v1/orders/members/\\d+/withdrawalCheck",
      "/internal/v1/payments/members/\\d+/withdrawalCheck",
      "/internal/v1/settlements/members/\\d+/withdrawalCheck"};
  private static final String PASS = """
      {"hasWithdrawalBlocker": false, "blockingReasons": [], "details": {}}""";
  private static final String BLOCK = """
      {"hasWithdrawalBlocker": true, "blockingReasons": ["ACTIVE_BUYER_ORDER"], "details": {}}""";

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    SharedPostgres.register(registry);
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
    SharedRedis.register(registry);
    // 세 내부 서비스와 카카오를 같은 WireMock으로 (경로가 달라 구분됨)
    registry.add("spring.http.serviceclient.market.base-url", EXTERNAL::baseUrl);
    registry.add("spring.http.serviceclient.payment.base-url", EXTERNAL::baseUrl);
    registry.add("spring.http.serviceclient.settlement.base-url", EXTERNAL::baseUrl);
    registry.add("member.kakao.api-base-url", EXTERNAL::baseUrl);
  }

  @Autowired WithdrawalService withdrawalService;
  @Autowired MemberRepository memberRepository;
  @Autowired AddressRepository addressRepository;
  @Autowired SellerRepository sellerRepository;
  @Autowired MemberWithdrawalRecordRepository recordRepository;
  @Autowired WithdrawalTokenStore tokenStore;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired MemberHmac memberHmac;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired StringRedisTemplate redisTemplate;
  @Autowired TransactionTemplate transactionTemplate;

  // 경합 · 롤백을 만들 때만 동작을 바꾸고, 평소에는 실제 빈 그대로 (테스트마다 원래대로 돌아감)
  @MockitoSpyBean WithdrawalCheckService withdrawalCheckService;
  @MockitoSpyBean DatabaseClock databaseClock;

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from member_withdrawal_record");
    jdbcTemplate.update("delete from member_token_revocation");
    jdbcTemplate.update("delete from outbox_event");
    jdbcTemplate.update("delete from seller");
    jdbcTemplate.update("delete from address");
    jdbcTemplate.update("delete from member");
    try (var connection = redisTemplate.getRequiredConnectionFactory().getConnection()) {
      connection.serverCommands().flushAll();
    }
    EXTERNAL.resetAll();
  }

  // ===== 성공 (S-B05) =====

  @Test
  @DisplayName("[S-B05] 로컬 회원 탈퇴: 개인정보 · 계좌 NULL · DELETED · 인증 버전 +1, 배송지 삭제 · 소개글 NULL, 탈퇴 기록은 익명화 전 값의 HMAC, 다른 이벤트 없음")
  void withdrawLocal() {
    Long memberId = givenSeller(Provider.LOCAL);
    Member before = memberRepository.findById(memberId).orElseThrow();
    long outboxBefore = outboxCount();
    allowAll();

    WithdrawalResult result = withdrawalService.withdraw(memberId, PASSWORD, null);

    assertThat(result.revocationCompleted()).isTrue();
    assertWithdrawn(memberId, before);
    // MemberUpdated · MemberAddressChanged가 나가지 않음 (#408에서 MemberWithdrawn 1건이 추가되면 이 기대값을 바꾼다)
    assertThat(outboxCount()).isEqualTo(outboxBefore);
    EXTERNAL.verify(0, postRequestedFor(urlPathEqualTo(UNLINK)));
  }

  @Test
  @DisplayName("[S-B05] 카카오 회원 탈퇴: 탈퇴 토큰 확인 → 연결 해제(회원번호로 한 번) → 익명화, 토큰은 소모")
  void withdrawKakao() {
    Long memberId = givenSeller(Provider.KAKAO);
    Member before = memberRepository.findById(memberId).orElseThrow();
    String token = givenWithdrawalToken(before);
    allowAll();
    stubUnlink(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"id\":1}"));

    withdrawalService.withdraw(memberId, null, token);

    assertWithdrawn(memberId, before);
    EXTERNAL.verify(1, postRequestedFor(urlPathEqualTo(UNLINK))
        .withRequestBody(containing("target_id=" + before.getProviderId())));
    assertThat(tokenStore.find(token)).isEmpty();
  }

  @Test
  @DisplayName("[S-B09] 이미 카카오 연결이 끊긴 회원(-101)도 탈퇴된다")
  void withdrawKakaoAlreadyUnlinked() {
    Long memberId = givenSeller(Provider.KAKAO);
    Member before = memberRepository.findById(memberId).orElseThrow();
    String token = givenWithdrawalToken(before);
    allowAll();
    stubUnlink(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
        .withBody("{\"msg\":\"NotRegisteredUserException\",\"code\":-101}"));

    withdrawalService.withdraw(memberId, null, token);

    assertWithdrawn(memberId, before);
  }

  // ===== 탈퇴 가능 여부 (S-B06) =====

  @Test
  @DisplayName("[S-B06] 탈퇴 조건 미충족이면 409 WITHDRAWAL_BLOCKED, 회원 그대로 · 카카오 토큰은 남아 다시 쓸 수 있음")
  void blocked() {
    Long memberId = givenSeller(Provider.KAKAO);
    Member before = memberRepository.findById(memberId).orElseThrow();
    String token = givenWithdrawalToken(before);
    stubChecks(PASS, BLOCK, PASS);

    assertRejected(() -> withdrawalService.withdraw(memberId, null, token), MemberErrorCode.WITHDRAWAL_BLOCKED);

    assertUnchanged(memberId, before);
    assertThat(tokenStore.find(token)).isPresent();
    EXTERNAL.verify(0, postRequestedFor(urlPathEqualTo(UNLINK)));
  }

  @Test
  @DisplayName("[S-B06] 탈퇴 가능 여부를 확인하지 못하면 503 WITHDRAWAL_CHECK_UNAVAILABLE, 회원 그대로")
  void checkUnavailable() {
    Long memberId = givenSeller(Provider.LOCAL);
    Member before = memberRepository.findById(memberId).orElseThrow();
    stubChecks(PASS, null, PASS);

    assertRejected(() -> withdrawalService.withdraw(memberId, PASSWORD, null), MemberErrorCode.WITHDRAWAL_CHECK_UNAVAILABLE);

    assertUnchanged(memberId, before);
  }

  // ===== 카카오 실패 · 롤백 (S-B09) =====

  @Test
  @DisplayName("[S-B09] 카카오 연결 해제가 실패하면 503 SOCIAL_UNLINK_FAILED, 회원 그대로 · 토큰은 소모돼 같은 토큰으로 다시 할 수 없음")
  void unlinkFailed() {
    Long memberId = givenSeller(Provider.KAKAO);
    Member before = memberRepository.findById(memberId).orElseThrow();
    String token = givenWithdrawalToken(before);
    allowAll();
    stubUnlink(aResponse().withStatus(500));

    assertRejected(() -> withdrawalService.withdraw(memberId, null, token), MemberErrorCode.SOCIAL_UNLINK_FAILED);

    assertUnchanged(memberId, before);
    assertRejected(() -> withdrawalService.withdraw(memberId, null, token), MemberErrorCode.INVALID_WITHDRAWAL_TOKEN);
  }

  @Test
  @DisplayName("[S-B09] 연결 해제 뒤 폐기 작업 저장 직전에 실패하면 탈퇴 기록 · 배송지 삭제 · 소개글 · 개인정보 · 인증 버전이 모두 롤백된다")
  void rollbackAfterUnlink() {
    Long memberId = givenSeller(Provider.KAKAO);
    Member before = memberRepository.findById(memberId).orElseThrow();
    String token = givenWithdrawalToken(before);
    allowAll();
    stubUnlink(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"id\":1}"));
    // 탈퇴 중 DB 시각은 두 번 읽힌다: ① 최종 처리의 탈퇴 시각 ② revoke()가 폐기 작업을 만들 때(saveAndFlush 직전).
    // ②에서 실패시키면 ①까지의 변경(탈퇴 기록 INSERT · 배송지 DELETE 실행 포함)이 끝난 뒤라 롤백을 확인할 수 있고,
    // saveAndFlush 전이라 결과 불명이 아니라 확인된 실패로 전달된다
    doCallRealMethod()
        .doThrow(new DataAccessResourceFailureException("폐기 작업 저장 실패 (테스트)"))
        .when(databaseClock).now();

    assertThatThrownBy(() -> withdrawalService.withdraw(memberId, null, token))
        .isInstanceOf(DataAccessResourceFailureException.class);

    assertUnchanged(memberId, before);
    assertThat(recordRepository.count()).isZero();
    EXTERNAL.verify(1, postRequestedFor(urlPathEqualTo(UNLINK)));
  }

  @Test
  @DisplayName("[S-B09] 연결 해제 뒤 DB가 롤백되면 재인증(새 탈퇴 토큰) 후 다시 탈퇴할 수 있고, 이미 끊긴 연결(400 + -101)은 성공으로 처리")
  void retryAfterRollback() {
    Long memberId = givenSeller(Provider.KAKAO);
    Member before = memberRepository.findById(memberId).orElseThrow();
    String firstToken = givenWithdrawalToken(before);
    allowAll();
    stubUnlink(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"id\":1}"));
    // 첫 시도: rollbackAfterUnlink와 같은 지점(폐기 작업 저장 직전)에서 실패. 그 뒤 호출은 다시 실제 DB 시각
    doCallRealMethod()
        .doThrow(new DataAccessResourceFailureException("폐기 작업 저장 실패 (테스트)"))
        .doCallRealMethod()
        .when(databaseClock).now();

    assertThatThrownBy(() -> withdrawalService.withdraw(memberId, null, firstToken))
        .isInstanceOf(DataAccessResourceFailureException.class);
    assertUnchanged(memberId, before);

    // 카카오에서는 이미 끊겼으므로 다시 부르면 400 + -101. 인증 버전은 롤백돼 그대로라 재인증으로 새 토큰을 받는 것과 같다
    stubUnlink(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
        .withBody("{\"msg\":\"NotRegisteredUserException\",\"code\":-101}"));
    String secondToken = givenWithdrawalToken(before);

    withdrawalService.withdraw(memberId, null, secondToken);

    assertWithdrawn(memberId, before);
    EXTERNAL.verify(2, postRequestedFor(urlPathEqualTo(UNLINK)));
  }

  // ===== 재확인 (S-A10) =====

  @Test
  @DisplayName("[S-A10] 비밀번호가 틀리면 400 INVALID_CURRENT_PASSWORD, 실패 횟수가 기록되고 탈퇴 가능 여부 확인을 부르지 않음")
  void wrongPassword() {
    Long memberId = givenSeller(Provider.LOCAL);

    assertRejected(() -> withdrawalService.withdraw(memberId, "wrong-password", null), MemberErrorCode.INVALID_CURRENT_PASSWORD);

    assertThat(redisTemplate.hasKey("auth:password:failures:" + memberId)).isTrue();
    EXTERNAL.verify(0, anyRequestedFor(anyUrl()));
  }

  @Test
  @DisplayName("[S-A10] 다섯 번 틀리면 그 뒤로는 맞는 비밀번호여도 429 PASSWORD_CHANGE_LOCKED")
  void lockedAfterFiveFailures() {
    Long memberId = givenSeller(Provider.LOCAL);
    for (int i = 0; i < 5; i++) {
      assertRejected(() -> withdrawalService.withdraw(memberId, "wrong-password", null), MemberErrorCode.INVALID_CURRENT_PASSWORD);
    }

    assertThatThrownBy(() -> withdrawalService.withdraw(memberId, PASSWORD, null))
        .isInstanceOfSatisfying(PasswordChangeLockedException.class, e -> {
          assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.PASSWORD_CHANGE_LOCKED);
          assertThat(e.getRetryAfterSeconds()).isPositive();
        });
  }

  @Test
  @DisplayName("[S-A10] 다른 회원의 탈퇴 토큰 · 발급 뒤 인증 버전이 바뀐 토큰 · 모르는 토큰은 400 INVALID_WITHDRAWAL_TOKEN")
  void invalidToken() {
    Long memberId = givenSeller(Provider.KAKAO);
    Member member = memberRepository.findById(memberId).orElseThrow();
    String othersToken = "others-token";
    tokenStore.save(othersToken, new VerificationBinding(memberId + 1000, member.getTokenVersion()), Duration.ofMinutes(10));
    String oldVersionToken = "old-version-token";
    tokenStore.save(oldVersionToken, new VerificationBinding(memberId, member.getTokenVersion() - 1), Duration.ofMinutes(10));

    assertRejected(() -> withdrawalService.withdraw(memberId, null, othersToken), MemberErrorCode.INVALID_WITHDRAWAL_TOKEN);
    assertRejected(() -> withdrawalService.withdraw(memberId, null, oldVersionToken), MemberErrorCode.INVALID_WITHDRAWAL_TOKEN);
    assertRejected(() -> withdrawalService.withdraw(memberId, null, "unknown-token"), MemberErrorCode.INVALID_WITHDRAWAL_TOKEN);
  }

  @Test
  @DisplayName("[S-A10] 회원 유형과 다른 재확인 값(로컬이 토큰 · 카카오가 비밀번호 · 둘 다 없음)은 400 BAD_REQUEST_DATA")
  void mismatchedReconfirmation() {
    Long localId = givenSeller(Provider.LOCAL);
    Long kakaoId = givenSeller(Provider.KAKAO);

    assertRejected(() -> withdrawalService.withdraw(localId, null, "token"), CommonErrorCode.BAD_REQUEST_DATA);
    assertRejected(() -> withdrawalService.withdraw(localId, PASSWORD, "token"), CommonErrorCode.BAD_REQUEST_DATA);
    assertRejected(() -> withdrawalService.withdraw(kakaoId, PASSWORD, null), CommonErrorCode.BAD_REQUEST_DATA);
    assertRejected(() -> withdrawalService.withdraw(kakaoId, null, null), CommonErrorCode.BAD_REQUEST_DATA);
  }

  // ===== 경합: 3곳 확인 도중 다른 트랜잭션이 커밋한 변경 =====

  @Test
  @DisplayName("재확인 뒤 비밀번호가 바뀌면(인증 버전 증가) 최종 잠금에서 400 INVALID_CURRENT_PASSWORD, 실패 횟수는 늘지 않음")
  void versionChangedDuringCheck() {
    Long memberId = givenSeller(Provider.LOCAL);
    Member before = memberRepository.findById(memberId).orElseThrow();
    allowAll();
    // 3곳 확인이 불리는 순간 다른 트랜잭션(자동 커밋)에서 인증 버전을 올린 뒤 실제 확인을 이어 간다
    doAnswer(invocation -> {
      jdbcTemplate.update("update member set token_version = token_version + 1 where id = ?", memberId);
      return invocation.callRealMethod();
    }).when(withdrawalCheckService).check(anyLong());

    assertRejected(() -> withdrawalService.withdraw(memberId, PASSWORD, null), MemberErrorCode.INVALID_CURRENT_PASSWORD);

    Member after = memberRepository.findById(memberId).orElseThrow();
    assertThat(after.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    assertThat(after.getEmail()).isEqualTo(before.getEmail());
    assertThat(redisTemplate.hasKey("auth:password:failures:" + memberId)).isFalse();
  }

  @Test
  @DisplayName("3곳 확인 도중 다른 요청이 먼저 탈퇴를 커밋하면 404, 그 요청의 탈퇴 기록 외에 기록이 더 생기지 않음")
  void withdrawnByOtherRequestDuringCheck() {
    Long memberId = givenSeller(Provider.KAKAO);
    Member before = memberRepository.findById(memberId).orElseThrow();
    String token = givenWithdrawalToken(before);
    allowAll();
    stubUnlink(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"id\":1}"));
    // 다른 요청(다른 탈퇴 토큰 · 웹훅)의 탈퇴가 먼저 커밋된 상태를 만든다: 익명화 + 그 요청의 탈퇴 기록
    doAnswer(invocation -> {
      jdbcTemplate.update("""
          update member set status = 'DELETED', email = null, phone = null, nickname = null, password = null,
                 provider_id = null, token_version = token_version + 1
           where id = ?""", memberId);
      jdbcTemplate.update("""
          insert into member_withdrawal_record (email_hash, phone_hash, withdrawn_at, created_at, updated_at)
          values (?, ?, now(), now(), now())""",
          memberHmac.emailHash(before.getEmail()), memberHmac.phoneHash(before.getPhone()));
      return invocation.callRealMethod();
    }).when(withdrawalCheckService).check(anyLong());

    assertRejected(() -> withdrawalService.withdraw(memberId, null, token), MemberErrorCode.MEMBER_NOT_FOUND);

    assertThat(recordRepository.count()).isEqualTo(1);
  }

  // ===== 사전 확인 (GET) =====

  @Test
  @DisplayName("사전 확인: 탈퇴 · 연결 해제(INACTIVE) 회원은 3곳을 부르지 않고 404")
  void checkNotActive() {
    Long deletedId = givenSeller(Provider.LOCAL);
    Long inactiveId = givenSeller(Provider.KAKAO);
    jdbcTemplate.update("update member set status = 'DELETED' where id = ?", deletedId);
    jdbcTemplate.update("update member set status = 'INACTIVE' where id = ?", inactiveId);

    assertRejected(() -> withdrawalService.check(deletedId), MemberErrorCode.MEMBER_NOT_FOUND);
    assertRejected(() -> withdrawalService.check(inactiveId), MemberErrorCode.MEMBER_NOT_FOUND);
    EXTERNAL.verify(0, anyRequestedFor(anyUrl()));
  }

  @Test
  @DisplayName("사전 확인: 차단이면 사유와 함께 BLOCKED, 확인 실패면 일부 사유도 보여 주지 않고 503")
  void checkResult() {
    Long memberId = givenSeller(Provider.LOCAL);
    stubChecks(PASS, BLOCK, PASS);
    WithdrawalCheckResult blocked = withdrawalService.check(memberId);
    assertThat(blocked.status()).isEqualTo(WithdrawalCheckStatus.BLOCKED);
    assertThat(blocked.blockers()).singleElement()
        .satisfies(b -> assertThat(b.reasons()).containsExactly("ACTIVE_BUYER_ORDER"));

    EXTERNAL.resetAll();
    stubChecks(BLOCK, null, PASS);
    assertRejected(() -> withdrawalService.check(memberId), MemberErrorCode.WITHDRAWAL_CHECK_UNAVAILABLE);
  }

  // ===== 도우미 =====

  // 회원 + 배송지 2개 + 판매자(소개글 있음)를 한 트랜잭션으로 만든다
  private Long givenSeller(Provider provider) {
    return transactionTemplate.execute(status -> {
      int n = SEQ.incrementAndGet();
      String email = "withdraw" + n + "@test.com";
      String phone = String.format("013%08d", n);
      Member member = provider == Provider.LOCAL
          ? Member.createLocal(email, passwordEncoder.encode(PASSWORD), "탈퇴" + n, phone,
              "004", "v1:test-account", null, "홍길동")
          : Member.createSocial(Provider.KAKAO, String.valueOf(9_000_000_000L + n), email, "탈퇴" + n, phone,
              "004", "v1:test-account", null, "홍길동");
      memberRepository.save(member);
      addressRepository.save(Address.create(member, "홍길동", "01012345678", "06236", "서울 강남구 1", null, null, true));
      addressRepository.save(Address.create(member, "홍길동", "01012345678", "06236", "서울 강남구 2", null, null, false));
      member.promoteToSeller();
      sellerRepository.save(Seller.register(member, "식물 판매합니다", SellerReviewConsentPolicy.VERSION, LocalDateTime.now()));
      return member.getId();
    });
  }

  // 이메일 인증 흐름(별도 테스트)은 건너뛰고 탈퇴 토큰만 직접 저장한다
  private String givenWithdrawalToken(Member member) {
    String token = "withdrawal-token-" + member.getId();
    tokenStore.save(token, new VerificationBinding(member.getId(), member.getTokenVersion()), Duration.ofMinutes(10));
    return token;
  }

  private void assertWithdrawn(Long memberId, Member before) {
    Member after = memberRepository.findById(memberId).orElseThrow();
    assertThat(after.getStatus()).isEqualTo(MemberStatus.DELETED);
    assertThat(after.getEmail()).isNull();
    assertThat(after.getPassword()).isNull();
    assertThat(after.getNickname()).isNull();
    assertThat(after.getPhone()).isNull();
    assertThat(after.getProviderId()).isNull();
    assertThat(after.getBankCode()).isNull();
    assertThat(after.getAccountNoEnc()).isNull();
    assertThat(after.getAccountHash()).isNull();
    assertThat(after.getAccountHolder()).isNull();
    assertThat(after.getAccountUpdatedAt()).isNull();
    assertThat(after.getProvider()).isEqualTo(before.getProvider());
    assertThat(after.getTokenVersion()).isEqualTo(before.getTokenVersion() + 1);
    assertThat(addressRepository.countByMemberId(memberId)).isZero();
    assertThat(sellerRepository.findById(memberId)).get().satisfies(s -> assertThat(s.getIntro()).isNull());
    assertThat(recordRepository.findAll()).singleElement().satisfies(r -> {
      assertThat(r.getEmailHash()).isEqualTo(memberHmac.emailHash(before.getEmail()));
      assertThat(r.getPhoneHash()).isEqualTo(memberHmac.phoneHash(before.getPhone()));
    });
  }

  private void assertUnchanged(Long memberId, Member before) {
    Member after = memberRepository.findById(memberId).orElseThrow();
    assertThat(after.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    assertThat(after.getEmail()).isEqualTo(before.getEmail());
    assertThat(after.getPhone()).isEqualTo(before.getPhone());
    assertThat(after.getAccountNoEnc()).isEqualTo(before.getAccountNoEnc());
    assertThat(after.getProviderId()).isEqualTo(before.getProviderId());
    assertThat(after.getTokenVersion()).isEqualTo(before.getTokenVersion());
    assertThat(addressRepository.countByMemberId(memberId)).isEqualTo(2);
    assertThat(sellerRepository.findById(memberId)).get().satisfies(s -> assertThat(s.getIntro()).isEqualTo("식물 판매합니다"));
  }

  private void allowAll() {
    stubChecks(PASS, PASS, PASS);
  }

  // market · payment · settlement 순서. null이면 그 서비스는 500(확인 실패)
  private void stubChecks(String market, String payment, String settlement) {
    String[] bodies = {market, payment, settlement};
    for (int i = 0; i < CHECK_PATHS.length; i++) {
      ResponseDefinitionBuilder response = bodies[i] == null
          ? aResponse().withStatus(500)
          : aResponse().withStatus(200).withHeader("Content-Type", "application/json")
              .withBody("{\"status\": 200, \"code\": \"OK\", \"message\": \"ok\", \"data\": " + bodies[i] + "}");
      EXTERNAL.stubFor(get(urlPathMatching(CHECK_PATHS[i])).willReturn(response));
    }
  }

  private void stubUnlink(ResponseDefinitionBuilder response) {
    EXTERNAL.stubFor(post(urlPathEqualTo(UNLINK)).willReturn(response));
  }

  private long outboxCount() {
    return jdbcTemplate.queryForObject("select count(*) from outbox_event", Long.class);
  }

  private static void assertRejected(ThrowingCallable call, ErrorCode code) {
    assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
        e -> assertThat(e.getErrorCode()).isEqualTo(code));
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

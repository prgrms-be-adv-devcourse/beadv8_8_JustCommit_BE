package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.security.JwtVerifier;
import com.justcommit.backend.common.security.TokenHash;
import com.justcommit.backend.member.auth.domain.RevocationReason;
import com.justcommit.backend.member.auth.domain.RevocationStatus;
import com.justcommit.backend.member.auth.domain.TokenRevocation;
import com.justcommit.backend.member.auth.infrastructure.redis.RefreshTokenRedisStore;
import com.justcommit.backend.member.auth.infrastructure.redis.RevokedMemberRedisStore;
import com.justcommit.backend.member.auth.infrastructure.repository.TokenRevocationRepository;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.common.testcontainers.SharedRedis;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;

/**
 * 토큰 폐기 공통 처리 (#409)를 실제 PostgreSQL · Redis로 확인한다.
 * Redis 기록 실패 · 상태 갱신 실패는 실제 빈을 감싼 스파이로 만든다 (테스트마다 원래 동작으로 되돌아감).
 * 상태 갱신은 TokenRevocationRecorder만 executeWithoutResult를 쓰므로, 그것만 실패시키면 폐기 트랜잭션은 그대로다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class TokenRevocationServiceIntegrationTest {

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final Duration RETENTION = Duration.ofMinutes(31);
  private static final Duration TTL = Duration.ofDays(1);
  private static final AtomicInteger SEQ = new AtomicInteger();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    SharedPostgres.register(registry);
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
    SharedRedis.register(registry);
  }

  @Autowired
  private TokenRevocationService revocationService;

  @Autowired
  private TokenRevocationRecorder revocationRecorder;

  @Autowired
  private LoginService loginService;

  @Autowired
  private TokenReissueService tokenReissueService;

  @Autowired
  private JwtVerifier jwtVerifier;

  @Autowired
  private TokenRevocationRepository revocationRepository;

  @Autowired
  private MemberRepository memberRepository;

  @MockitoSpyBean
  private RefreshTokenRedisStore refreshTokenRedisStore;

  @Autowired
  private StringRedisTemplate redisTemplate;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @MockitoSpyBean
  private DatabaseClock databaseClock;

  @MockitoSpyBean
  private RevokedMemberRedisStore revokedMemberRedisStore;

  @MockitoSpyBean
  private TransactionTemplate transactionTemplate;

  @BeforeEach
  void flushRedis() {
    try (var connection = redisTemplate.getRequiredConnectionFactory().getConnection()) {
      connection.serverCommands().flushAll();
    }
  }

  @Test
  @DisplayName("[S-A08] 성공: 커밋 뒤 Redis에 폐기 시각을 기록하고 COMPLETED, 여러 기기의 옛 버전 Refresh가 모두 지워진다")
  void revokeCompleted() {
    Member member = saveMember();
    refreshTokenRedisStore.save("old-refresh", member.getId(), 0, TTL);
    refreshTokenRedisStore.save("old-refresh-other-device", member.getId(), 0, TTL);

    RevocationResult result = revocationService.revoke(member.getId(), RevocationReason.PASSWORD_CHANGED, m -> { });

    assertThat(result).isEqualTo(RevocationResult.COMPLETED);
    assertThat(memberRepository.findById(member.getId()).orElseThrow().getTokenVersion()).isEqualTo(1);
    TokenRevocation revocation = onlyRevocationOf(member.getId());
    assertThat(revocation.getStatus()).isEqualTo(RevocationStatus.RECORDED);
    assertThat(revocation.getTokenVersion()).isEqualTo(1);
    assertThat(revocation.getCutoffAt().getNano()).isZero();
    assertThat(Duration.between(revocation.getCutoffAt(), revocation.getRetainUntil())).isEqualTo(RETENTION);
    assertThat(redisTemplate.opsForValue().get(revokedKey(member.getId())))
        .isEqualTo(String.valueOf(revocation.getCutoffAt().getEpochSecond()));
    assertThat(redisTemplate.getExpire(revokedKey(member.getId()), TimeUnit.MILLISECONDS))
        .isGreaterThan(Duration.ofMinutes(30).toMillis());
    assertThat(refreshTokenRedisStore.findOwner("old-refresh")).isEmpty();
    assertThat(refreshTokenRedisStore.findOwner("old-refresh-other-device")).isEmpty();
  }

  @Test
  @DisplayName("[S-A08 계약] 실제 로그인 뒤 revoke(): Redis 폐기 값은 작업의 cutoffAt(초)이고 기존 Access는 iat <= 값, 폐기 전 Refresh는 재발급 실패")
  void revokeWritesGatewayContract() {
    Member member = saveMember();
    LoginResult before = loginService.login(new LoginCommand(member.getEmail(), "plant1234"));

    assertThat(revocationService.revoke(member.getId(), RevocationReason.PASSWORD_CHANGED, m -> { }))
        .isEqualTo(RevocationResult.COMPLETED);

    // Gateway TokenRevocationChecker와 같은 방식으로 값을 읽음 (Long.parseLong)
    long cutoff = Long.parseLong(redisTemplate.opsForValue().get(revokedKey(member.getId())));
    assertThat(cutoff).isEqualTo(onlyRevocationOf(member.getId()).getCutoffAt().getEpochSecond());
    // Gateway와 같은 검증기로 iat(초)를 꺼냄 → Gateway 규칙(iat <= 끊은 시각이면 거절)상 거절 대상
    assertThat(jwtVerifier.verifyAccessToken(before.accessToken()).issuedAt()).isLessThanOrEqualTo(cutoff);
    assertThatThrownBy(() -> tokenReissueService.reissue(before.refreshToken()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_REFRESH_TOKEN);

    // 폐기 뒤 새 로그인: iat >= 끊은 시각. 같은 초면 거절 · 다음 초부터 통과하는 경계는 Gateway S-A08이 확인
    LoginResult after = loginService.login(new LoginCommand(member.getEmail(), "plant1234"));
    assertThat(jwtVerifier.verifyAccessToken(after.accessToken()).issuedAt()).isGreaterThanOrEqualTo(cutoff);
  }

  @Test
  @DisplayName("[S-A08] 옛 Refresh 삭제가 실패해도 폐기는 성공하고, 남아 있는 옛 Refresh는 인증 버전 비교로 재발급 실패")
  void refreshDeleteFailureStillRejectedByVersion() {
    Member member = saveMember();
    LoginResult before = loginService.login(new LoginCommand(member.getEmail(), "plant1234"));
    doThrow(new RedisConnectionFailureException("옛 Refresh 삭제 실패 (테스트)"))
        .when(refreshTokenRedisStore).deleteOlderVersions(anyLong(), anyInt());

    // 비밀번호 변경 경로(회원은 ACTIVE로 남음)라 재발급을 막는 것은 상태 검사가 아니라 인증 버전 비교다
    assertThat(revocationService.revoke(member.getId(), RevocationReason.PASSWORD_CHANGED, m -> { }))
        .isEqualTo(RevocationResult.COMPLETED);

    assertThat(redisTemplate.hasKey("auth:refresh:" + TokenHash.sha256Hex(before.refreshToken()))).isTrue();
    assertThatThrownBy(() -> tokenReissueService.reissue(before.refreshToken()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_REFRESH_TOKEN);
  }

  @Test
  @DisplayName("DB 커밋 뒤 Redis 기록 실패: PENDING, 변경은 커밋되고 작업은 PENDING으로 남아 재처리를 기다린다")
  void redisFailureAfterCommit() {
    Member member = saveMember();
    doThrow(new RedisConnectionFailureException("redis down"))
        .when(revokedMemberRedisStore).recordCutoff(anyLong(), any(), any());

    RevocationResult result = revocationService.revoke(member.getId(), RevocationReason.WITHDRAWN, m -> { });

    assertThat(result).isEqualTo(RevocationResult.PENDING);
    assertThat(memberRepository.findById(member.getId()).orElseThrow().getTokenVersion()).isEqualTo(1);
    TokenRevocation revocation = onlyRevocationOf(member.getId());
    assertThat(revocation.getStatus()).isEqualTo(RevocationStatus.PENDING);
    assertThat(revocation.getAttemptCount()).isEqualTo(1);
    assertThat(revocation.getLastError()).contains("redis down");
    assertThat(revocation.getNextAttemptAt()).isAfter(revocation.getCutoffAt());
    assertThat(redisTemplate.hasKey(revokedKey(member.getId()))).isFalse();
  }

  @Test
  @DisplayName("Redis 기록 성공 뒤 상태 갱신 실패: COMPLETED, 작업은 PENDING으로 남고 같은 Lua로 다시 기록해도 값은 그대로다")
  void statusUpdateFailureAfterRecord() {
    Member member = saveMember();
    doThrow(new CannotCreateTransactionException("db down"))
        .when(transactionTemplate).executeWithoutResult(any());

    RevocationResult result = revocationService.revoke(member.getId(), RevocationReason.PASSWORD_CHANGED, m -> { });

    assertThat(result).isEqualTo(RevocationResult.COMPLETED);
    TokenRevocation revocation = onlyRevocationOf(member.getId());
    assertThat(revocation.getStatus()).isEqualTo(RevocationStatus.PENDING);
    String recorded = redisTemplate.opsForValue().get(revokedKey(member.getId()));
    assertThat(recorded).isEqualTo(String.valueOf(revocation.getCutoffAt().getEpochSecond()));

    // 재처리: 같은 Lua로 다시 기록 → 값은 그대로, 이번엔 상태도 RECORDED
    Mockito.reset(transactionTemplate);
    assertThat(revocationRecorder.record(revocation)).isTrue();
    assertThat(redisTemplate.opsForValue().get(revokedKey(member.getId()))).isEqualTo(recorded);
    assertThat(onlyRevocationOf(member.getId()).getStatus()).isEqualTo(RevocationStatus.RECORDED);
  }

  @Test
  @DisplayName("커밋 뒤 DB 시각 조회와 실패 갱신이 모두 실패해도 결과는 PENDING, 작업은 커밋된 PENDING 그대로 남는다")
  void clockAndFailureUpdateBothFailAfterCommit() {
    Member member = saveMember();
    // 첫 호출(폐기 트랜잭션 안의 폐기 시각)은 실제 DB 시각, 두 번째(커밋 뒤 Recorder)는 실패
    doCallRealMethod().doThrow(new DataAccessResourceFailureException("db down")).when(databaseClock).now();
    doThrow(new CannotCreateTransactionException("db down"))
        .when(transactionTemplate).executeWithoutResult(any());

    RevocationResult result = revocationService.revoke(member.getId(), RevocationReason.PASSWORD_CHANGED, m -> { });

    assertThat(result).isEqualTo(RevocationResult.PENDING);
    assertThat(memberRepository.findById(member.getId()).orElseThrow().getTokenVersion()).isEqualTo(1);
    TokenRevocation revocation = onlyRevocationOf(member.getId());
    assertThat(revocation.getStatus()).isEqualTo(RevocationStatus.PENDING);
    assertThat(revocation.getAttemptCount()).isZero();
    assertThat(revocation.getNextAttemptAt()).isEqualTo(revocation.getCutoffAt().plus(TokenRevocation.FIRST_RECORD_GRACE));
    assertThat(revocation.getLastError()).isNull();
    assertThat(redisTemplate.hasKey(revokedKey(member.getId()))).isFalse();
    then(revokedMemberRedisStore).should(never()).recordCutoff(anyLong(), any(), any());
  }

  @Test
  @DisplayName("이미 RECORDED인 작업에 늦게 온 실패 갱신은 0행이고 상태 · 시도 횟수 · 오류를 바꾸지 않는다")
  void lateFailureUpdateDoesNotRevertRecorded() {
    Member member = saveMember();
    assertThat(revocationService.revoke(member.getId(), RevocationReason.PASSWORD_CHANGED, m -> { }))
        .isEqualTo(RevocationResult.COMPLETED);
    TokenRevocation recorded = onlyRevocationOf(member.getId());
    assertThat(recorded.getStatus()).isEqualTo(RevocationStatus.RECORDED);

    Integer changed = transactionTemplate.execute(status ->
        revocationRepository.recordFailure(recorded.getId(), "늦은 실패",
            recorded.getCutoffAt().plus(TokenRevocation.FIRST_RECORD_GRACE), LocalDateTime.now()));

    assertThat(changed).isZero();
    TokenRevocation after = onlyRevocationOf(member.getId());
    assertThat(after.getStatus()).isEqualTo(RevocationStatus.RECORDED);
    assertThat(after.getAttemptCount()).isZero();
    assertThat(after.getLastError()).isNull();
    assertThat(after.getNextAttemptAt()).isNull();
  }

  @Test
  @DisplayName("늦게 재시도된 옛 폐기 작업은 Redis의 더 최신 폐기 시각을 덮어쓰지 않는다")
  void lateOldRevocationDoesNotOverwriteNewer() {
    Member member = saveMember();
    Instant now = databaseClock.now();
    TokenRevocation older = revocationRepository.save(
        TokenRevocation.create(member.getId(), RevocationReason.PASSWORD_CHANGED, 1, now, RETENTION));
    TokenRevocation newer = revocationRepository.save(
        TokenRevocation.create(member.getId(), RevocationReason.WITHDRAWN, 2, now.plusSeconds(10), RETENTION));

    assertThat(revocationRecorder.record(newer)).isTrue();
    assertThat(revocationRecorder.record(older)).isTrue();

    assertThat(redisTemplate.opsForValue().get(revokedKey(member.getId())))
        .isEqualTo(String.valueOf(newer.getCutoffAt().getEpochSecond()));
    assertThat(revocationRepository.findById(older.getId()).orElseThrow().getStatus())
        .isEqualTo(RevocationStatus.RECORDED);
  }

  @Test
  @DisplayName("보관 기한이 지난 작업은 Redis에 기록하지 않고 EXPIRED로 끝낸다")
  void expiredRevocationIsNotRecorded() {
    Member member = saveMember();
    Instant longAgo = databaseClock.now().minus(RETENTION).minusSeconds(5);
    TokenRevocation expired = revocationRepository.save(
        TokenRevocation.create(member.getId(), RevocationReason.PASSWORD_CHANGED, 1, longAgo, RETENTION));

    assertThat(revocationRecorder.record(expired)).isTrue();

    assertThat(revocationRepository.findById(expired.getId()).orElseThrow().getStatus())
        .isEqualTo(RevocationStatus.EXPIRED);
    assertThat(redisTemplate.hasKey(revokedKey(member.getId()))).isFalse();
  }

  @Test
  @DisplayName("[#422] SOCIAL_UNLINKED 폐기: 콜백에서 계산한 폐기 후 버전이 커밋된 인증 버전 · 폐기 작업 버전과 같고, 회원은 INACTIVE")
  void socialUnlinkVersionContract() {
    Member member = saveMember();
    AtomicInteger expected = new AtomicInteger(-1);

    RevocationResult result = revocationService.revoke(member.getId(), RevocationReason.SOCIAL_UNLINKED, m -> {
      m.markSocialUnlinked();
      expected.set(TokenRevocationService.versionAfterRevocation(m));
    });

    assertThat(result).isEqualTo(RevocationResult.COMPLETED);
    Member after = memberRepository.findById(member.getId()).orElseThrow();
    assertThat(after.getStatus()).isEqualTo(MemberStatus.INACTIVE);
    assertThat(after.getTokenVersion()).isEqualTo(expected.get()).isEqualTo(1);
    TokenRevocation revocation = onlyRevocationOf(member.getId());
    assertThat(revocation.getReason()).isEqualTo(RevocationReason.SOCIAL_UNLINKED);
    assertThat(revocation.getTokenVersion()).isEqualTo(expected.get());
  }

  @Test
  @DisplayName("[#422] SOCIAL_UNLINKED 폐기 콜백이 예외로 끝나면 INACTIVE 전환 · 인증 버전 · 폐기 작업 모두 롤백")
  void socialUnlinkRollsBack() {
    Member member = saveMember();

    assertThatThrownBy(() -> revocationService.revoke(member.getId(), RevocationReason.SOCIAL_UNLINKED, m -> {
      m.markSocialUnlinked();
      throw new IllegalStateException("기록 확인 실패");
    })).isInstanceOf(IllegalStateException.class).hasMessage("기록 확인 실패");

    Member after = memberRepository.findById(member.getId()).orElseThrow();
    assertThat(after.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    assertThat(after.getTokenVersion()).isZero();
    assertThat(revocationsOf(member.getId())).isEmpty();
  }

  @Test
  @DisplayName("변경 중 예외: 롤백되어 인증 버전 · 폐기 작업 · Redis 모두 그대로다")
  void changeFailureRollsBack() {
    Member member = saveMember();

    assertThatThrownBy(() -> revocationService.revoke(member.getId(), RevocationReason.PASSWORD_CHANGED, m -> {
      throw new IllegalStateException("변경 실패");
    })).isInstanceOf(IllegalStateException.class).hasMessage("변경 실패");

    assertThat(memberRepository.findById(member.getId()).orElseThrow().getTokenVersion()).isZero();
    assertThat(revocationsOf(member.getId())).isEmpty();
    assertThat(redisTemplate.hasKey(revokedKey(member.getId()))).isFalse();
  }

  @Test
  @DisplayName("트랜잭션 안에서 호출하면 IllegalStateException, 아무것도 바뀌지 않는다")
  void revokeInsideTransactionRejected() {
    Member member = saveMember();

    assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
        revocationService.revoke(member.getId(), RevocationReason.PASSWORD_CHANGED, m -> { })))
        .isInstanceOf(IllegalStateException.class);

    assertThat(memberRepository.findById(member.getId()).orElseThrow().getTokenVersion()).isZero();
    assertThat(revocationsOf(member.getId())).isEmpty();
  }

  @Test
  @DisplayName("없는 회원이면 MEMBER_NOT_FOUND")
  void unknownMember() {
    assertThatThrownBy(() -> revocationService.revoke(Long.MAX_VALUE, RevocationReason.PASSWORD_CHANGED, m -> { }))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
  }

  private Member saveMember() {
    int n = SEQ.incrementAndGet();
    return memberRepository.save(Member.createLocal(
        "revoke" + n + "@test.com", passwordEncoder.encode("plant1234"), "폐기" + n, String.format("013%08d", n),
        "004", "v1:test-account", null, "홍길동"));
  }

  private List<TokenRevocation> revocationsOf(Long memberId) {
    return revocationRepository.findAll().stream().filter(r -> r.getMemberId().equals(memberId)).toList();
  }

  private TokenRevocation onlyRevocationOf(Long memberId) {
    List<TokenRevocation> revocations = revocationsOf(memberId);
    assertThat(revocations).hasSize(1);
    return revocations.get(0);
  }

  private static String revokedKey(Long memberId) {
    return "auth:revoked-member:" + memberId;
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

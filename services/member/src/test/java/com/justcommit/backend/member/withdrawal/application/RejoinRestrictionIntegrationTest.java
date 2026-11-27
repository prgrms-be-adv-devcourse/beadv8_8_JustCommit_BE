package com.justcommit.backend.member.withdrawal.application;

import com.justcommit.backend.member.support.MemberIntegrationTest;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.address.infrastructure.AddressRepository;
import com.justcommit.backend.member.auth.domain.EmailPurpose;
import com.justcommit.backend.member.auth.infrastructure.jwt.JwtProvider;
import com.justcommit.backend.member.auth.infrastructure.redis.EmailVerificationRedisStore;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.crypto.MemberHmac;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.member.application.SignupCommand;
import com.justcommit.backend.member.member.application.SignupService;
import com.justcommit.backend.member.member.application.SocialSignupCommand;
import com.justcommit.backend.member.member.application.SocialSignupService;
import com.justcommit.backend.member.withdrawal.domain.MemberWithdrawalRecord;
import com.justcommit.backend.member.withdrawal.infrastructure.repository.MemberWithdrawalRecordRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * 재가입 제한 (#407)을 실제 PostgreSQL로 확인한다.
 * - S-B07: 이메일만 · 휴대폰만 같아도 거절, 30일이 지나면 가입 가능, 소셜 가입도 같은 판정
 * - S-B08: 탈퇴 커밋 전에는 기존 중복 검사에, 커밋 후에는 재가입 제한에 걸려 우회되지 않음
 * 탈퇴 기록은 탈퇴 API를 만들기 전이라 리포지토리로 직접 넣는다.
 */
class RejoinRestrictionIntegrationTest extends MemberIntegrationTest {

  private static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired private SignupService signupService;
  @Autowired private SocialSignupService socialSignupService;
  @Autowired private JwtProvider jwtProvider;
  @Autowired private EmailVerificationRedisStore verificationStore;
  @Autowired private MemberWithdrawalRecordRepository recordRepository;
  @Autowired private MemberRepository memberRepository;
  @Autowired private AddressRepository addressRepository;
  @Autowired private MemberHmac memberHmac;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private StringRedisTemplate redisTemplate;

  // Redis는 모듈의 테스트가 함께 쓰므로 테스트마다 비운다 (docs/testing/strategy.md 「통합 테스트 컨테이너 규칙」)
  @AfterEach
  void flushRedis() {
    try (var connection = redisTemplate.getRequiredConnectionFactory().getConnection()) {
      connection.serverCommands().flushAll();
    }
  }

  @Test
  @DisplayName("[S-B07] 30일 안에 탈퇴한 이메일이면 409 REJOIN_RESTRICTED, 회원 · 기본 배송지 · 가입 이벤트가 남지 않음")
  void rejectSameEmail() {
    String email = "rejoin-email@test.com";
    saveWithdrawal(email, phone(), databaseClock.now());
    long addresses = addressRepository.count();
    long outbox = outboxCount();

    assertRejected(() -> signup(email, phone()), MemberErrorCode.REJOIN_RESTRICTED);

    assertThat(memberRepository.existsByEmail(email)).isFalse();
    assertThat(addressRepository.count()).isEqualTo(addresses);
    assertThat(outboxCount()).isEqualTo(outbox);
  }

  @Test
  @DisplayName("[S-B07] 30일 안에 탈퇴한 휴대폰이면 이메일이 달라도 409 REJOIN_RESTRICTED")
  void rejectSamePhone() {
    String phone = phone();
    saveWithdrawal("old-phone@test.com", phone, databaseClock.now());

    assertRejected(() -> signup("new-phone@test.com", phone), MemberErrorCode.REJOIN_RESTRICTED);
    assertThat(memberRepository.existsByEmail("new-phone@test.com")).isFalse();
  }

  @Test
  @DisplayName("[S-B07] 탈퇴한 지 30일이 지났으면 같은 이메일 · 휴대폰으로 가입할 수 있음 (정리 전 기록이 남아 있어도)")
  void allowAfterPeriod() {
    String email = "rejoin-later@test.com";
    String phone = phone();
    saveWithdrawal(email, phone, databaseClock.now().minus(RejoinRestriction.PERIOD).minusSeconds(60));

    signup(email, phone);

    assertThat(memberRepository.existsByEmail(email)).isTrue();
  }

  @Test
  @DisplayName("[S-B07] 소셜 가입도 30일 안에 탈퇴한 이메일이면 409 REJOIN_RESTRICTED")
  void rejectSocialSignup() {
    String email = "rejoin-social@test.com";
    saveWithdrawal(email, phone(), databaseClock.now());
    String signupToken = jwtProvider.createSignupToken("KAKAO", "kakao-" + SEQ.incrementAndGet(), email);

    assertRejected(() -> socialSignupService.signup(signupToken, socialCommand(phone())), MemberErrorCode.REJOIN_RESTRICTED);
    assertThat(memberRepository.existsByEmail(email)).isFalse();
  }

  @Test
  @DisplayName("[S-B08] 커밋 전후: 탈퇴 커밋 전에 시작한 가입은 중복 검사(409 DUPLICATE_EMAIL), 커밋 후에 시작한 가입은 재가입 제한(409 REJOIN_RESTRICTED)")
  void concurrentWithdrawalAndSignup() throws Exception {
    String email = "race@test.com";
    String phone = phone();
    Member member = memberRepository.save(Member.createLocal(
        email, "encoded-password", "경쟁" + SEQ.incrementAndGet(), phone, "004", "v1:test-account", null, "홍길동"));

    CountDownLatch flushed = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      // 탈퇴 트랜잭션(다른 스레드): 익명화와 탈퇴 기록을 DB에 반영(flush)한 뒤 커밋하지 않고 기다린다
      Future<?> withdrawal = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
        jdbcTemplate.update("update member set status = 'DELETED', email = null, phone = null where id = ?",
            member.getId());
        recordRepository.saveAndFlush(MemberWithdrawalRecord.create(
            memberHmac.emailHash(email), memberHmac.phoneHash(phone), databaseClock.now()));
        flushed.countDown();
        awaitQuietly(release);
      }));
      assertThat(flushed.await(10, TimeUnit.SECONDS)).isTrue();

      // 커밋 전: 다른 트랜잭션에는 기존 이메일이 그대로 보여 중복 검사에 걸린다
      assertRejected(() -> signup(email, phone), MemberErrorCode.DUPLICATE_EMAIL);

      release.countDown();
      withdrawal.get(10, TimeUnit.SECONDS);

      // 커밋 후: 이메일 · 휴대폰은 NULL이 됐지만 탈퇴 기록이 함께 커밋돼 재가입 제한에 걸린다
      assertRejected(() -> signup(email, phone), MemberErrorCode.REJOIN_RESTRICTED);
      assertThat(memberRepository.existsByEmail(email)).isFalse();
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("[S-B08] 사전 검사 통과 후 가입 · 탈퇴가 끼어드는 경합: 검사를 통과한 가입 A 사이에 같은 이메일 · 휴대폰의 가입 B와 탈퇴가 커밋되면, A는 INSERT 뒤 재가입 제한으로 409 REJOIN_RESTRICTED · 회원 · 배송지 · 가입 이벤트 모두 롤백")
  void signupRaceWithJoinAndWithdrawal() throws Exception {
    String email = "race-insert@test.com";
    String phone = phone();
    String accountA = "300000000001"; // 가입 A만 멈추게 하려고 계좌번호로 구분 (B는 다른 계좌번호)
    CountDownLatch checked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(invocation -> {
      if (accountA.equals(invocation.getArgument(0))) {
        checked.countDown();
        awaitQuietly(release);
      }
      return invocation.callRealMethod();
    }).when(accountCipher).encrypt(anyString());

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      // 가입 A(다른 스레드): 같은 이메일 · 휴대폰 회원이 아직 없어 사전 중복 검사를 통과하고, INSERT 직전(계좌 암호화)에서 멈춘다
      verificationStore.saveVerified(EmailPurpose.SIGNUP, email, Duration.ofMinutes(30));
      Future<Throwable> signupA = executor.submit(() -> {
        try {
          signupService.signup(command(email, phone, "가입A" + SEQ.incrementAndGet(), accountA));
          return null;
        } catch (Throwable t) {
          return t;
        }
      });
      assertThat(checked.await(10, TimeUnit.SECONDS)).isTrue();

      // 그 사이 같은 이메일 · 휴대폰으로 가입 B가 커밋되고, 이어서 B의 탈퇴(이메일 · 휴대폰 NULL + 탈퇴 기록)가 커밋된다
      signup(email, phone);
      Long memberB = jdbcTemplate.queryForObject("select id from member where email = ?", Long.class, email);
      transactionTemplate.executeWithoutResult(status -> {
        jdbcTemplate.update("update member set status = 'DELETED', email = null, phone = null where id = ?", memberB);
        recordRepository.save(MemberWithdrawalRecord.create(
            memberHmac.emailHash(email), memberHmac.phoneHash(phone), databaseClock.now()));
      });
      long members = memberRepository.count();
      long addresses = addressRepository.count();
      long outbox = outboxCount();

      // A 재개: B의 이메일 · 휴대폰이 NULL이라 UNIQUE 충돌 없이 INSERT되지만, 바로 뒤 탈퇴 기록 조회가 막아 전부 롤백
      release.countDown();
      assertThat(signupA.get(10, TimeUnit.SECONDS)).isInstanceOfSatisfying(BusinessException.class,
          e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.REJOIN_RESTRICTED));
      assertThat(memberRepository.count()).isEqualTo(members);
      assertThat(addressRepository.count()).isEqualTo(addresses);
      assertThat(outboxCount()).isEqualTo(outbox);
      assertThat(memberRepository.existsByEmail(email)).isFalse();
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  private static SignupCommand command(String email, String phone, String nickname, String accountNo) {
    return new SignupCommand(email, "plant1234", nickname, phone, "004", accountNo, "홍길동",
        "홍길동", "01099998888", "12345", "서울시 어딘가 1", null, null);
  }

  private void signup(String email, String phone) {
    int n = SEQ.incrementAndGet();
    verificationStore.saveVerified(EmailPurpose.SIGNUP, email, Duration.ofMinutes(30));
    signupService.signup(new SignupCommand(email, "plant1234", "재가입" + n, phone,
        "004", String.format("1%011d", n), "홍길동",
        "홍길동", "01099998888", "12345", "서울시 어딘가 1", null, null));
  }

  private SocialSignupCommand socialCommand(String phone) {
    int n = SEQ.incrementAndGet();
    return new SocialSignupCommand("소셜" + n, phone, "004", String.format("2%011d", n), "홍길동",
        "홍길동", "01099998888", "12345", "서울시 어딘가 1", null, null);
  }

  private void saveWithdrawal(String email, String phone, Instant withdrawnAt) {
    recordRepository.save(MemberWithdrawalRecord.create(memberHmac.emailHash(email), memberHmac.phoneHash(phone), withdrawnAt));
  }

  private static String phone() {
    return String.format("016%08d", SEQ.incrementAndGet());
  }

  private long outboxCount() {
    return jdbcTemplate.queryForObject("select count(*) from outbox_event", Long.class);
  }

  private static void assertRejected(ThrowingCallable call, MemberErrorCode code) {
    assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
        e -> assertThat(e.getErrorCode()).isEqualTo(code));
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}

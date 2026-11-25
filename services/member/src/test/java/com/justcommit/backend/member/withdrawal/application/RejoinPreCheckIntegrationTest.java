package com.justcommit.backend.member.withdrawal.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.common.testcontainers.SharedRedis;
import com.justcommit.backend.member.auth.application.EmailVerificationService;
import com.justcommit.backend.member.auth.application.SocialLoginCommand;
import com.justcommit.backend.member.auth.application.SocialLoginResult;
import com.justcommit.backend.member.auth.application.SocialLoginService;
import com.justcommit.backend.member.auth.domain.EmailPurpose;
import com.justcommit.backend.member.auth.infrastructure.jwt.JwtProvider;
import com.justcommit.backend.member.auth.infrastructure.mail.VerificationMailSender;
import com.justcommit.backend.member.auth.infrastructure.redis.EmailVerificationRedisStore;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.crypto.MemberHmac;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.timeout;

/**
 * 재가입 제한 앞 단계 안내 (#611)를 실제 PostgreSQL · Redis로 확인한다.
 * - 1단계 가입 인증번호 발송(이메일) · 2단계 휴대폰 중복 확인(휴대폰) · 카카오 신규 가입 판정(이메일)에서 각 오류로 안내
 * - 앞 단계를 건너뛴 요청은 최종 가입 API의 기존 REJOIN_RESTRICTED가 막음
 * - 정확히 30일 전 탈퇴는 모든 단계 통과, 30일이 안 됐으면 모든 단계 차단 (DB 시각을 고정해 경계를 맞춤)
 * 탈퇴 기록은 RejoinRestrictionIntegrationTest처럼 리포지토리로 직접 넣는다. 메일은 실제로 보내지 않는다
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class RejoinPreCheckIntegrationTest {

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final AtomicInteger SEQ = new AtomicInteger();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    SharedPostgres.register(registry);
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
    SharedRedis.register(registry);
  }

  @Autowired private EmailVerificationService emailVerificationService;
  @Autowired private SignupService signupService;
  @Autowired private SocialLoginService socialLoginService;
  @Autowired private SocialSignupService socialSignupService;
  @Autowired private JwtProvider jwtProvider;
  @Autowired private EmailVerificationRedisStore verificationStore;
  @Autowired private MemberWithdrawalRecordRepository recordRepository;
  @Autowired private MemberRepository memberRepository;
  @Autowired private MemberHmac memberHmac;
  @Autowired private StringRedisTemplate redisTemplate;
  @MockitoBean private VerificationMailSender mailSender;
  // 경계 테스트에서만 now()를 고정한다. 평소에는 실제 DB 시각
  @MockitoSpyBean private DatabaseClock databaseClock;

  // Redis는 모듈의 테스트가 함께 쓰므로 테스트마다 비운다 (docs/testing/strategy.md 「통합 테스트 컨테이너 규칙」)
  @AfterEach
  void flushRedis() {
    try (var connection = redisTemplate.getRequiredConnectionFactory().getConnection()) {
      connection.serverCommands().flushAll();
    }
  }

  @Test
  @DisplayName("1단계: 30일 안에 탈퇴한 이메일로 인증번호를 요청하면 409 REJOIN_RESTRICTED_EMAIL, 메일 · 발송 예약 · 번호 저장 없음")
  void emailStageBlocked() {
    String email = email();
    saveWithdrawal(email, phone(), databaseClock.now());

    assertRejected(() -> emailVerificationService.sendCode(email, EmailPurpose.SIGNUP),
        MemberErrorCode.REJOIN_RESTRICTED_EMAIL);

    assertThat(redisTemplate.keys("auth:email:*" + email + "*")).isEmpty();
    then(mailSender).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("2단계: 다른 이메일로 1단계를 통과한 뒤 30일 안에 탈퇴한 휴대폰을 확인하면 409 REJOIN_RESTRICTED_PHONE")
  void phoneStageBlockedAfterOtherEmailPasses() {
    String phone = phone();
    saveWithdrawal(email(), phone, databaseClock.now());
    String newEmail = email();

    emailVerificationService.sendCode(newEmail, EmailPurpose.SIGNUP);
    then(mailSender).should(timeout(5000)).send(eq(newEmail), anyString(), any(), eq(EmailPurpose.SIGNUP));

    assertRejected(() -> signupService.isPhoneAvailable(phone), MemberErrorCode.REJOIN_RESTRICTED_PHONE);
  }

  @Test
  @DisplayName("카카오: 신규 판정된 계정의 이메일이 30일 안에 탈퇴한 이메일이면 409 REJOIN_RESTRICTED_EMAIL, 가입 토큰 · 회원 없음")
  void kakaoStageBlocked() {
    String email = email();
    saveWithdrawal(email, phone(), databaseClock.now());

    assertRejected(() -> socialLoginService.login(new SocialLoginCommand(Provider.KAKAO, kakaoId(), email)),
        MemberErrorCode.REJOIN_RESTRICTED_EMAIL);
    assertThat(memberRepository.existsByEmail(email)).isFalse();
  }

  @Test
  @DisplayName("앞 단계를 건너뜀: 인증 완료 표시만 있고 휴대폰 확인을 하지 않은 로컬 가입은 최종 단계에서 409 REJOIN_RESTRICTED")
  void localSignupSkippingPreChecks() {
    String phone = phone();
    saveWithdrawal(email(), phone, databaseClock.now());
    String email = email();

    assertRejected(() -> signup(email, phone), MemberErrorCode.REJOIN_RESTRICTED);
    assertThat(memberRepository.existsByEmail(email)).isFalse();
  }

  @Test
  @DisplayName("앞 단계를 건너뜀: 카카오 콜백 없이 만든 가입 토큰으로 소셜 가입하면 최종 단계에서 409 REJOIN_RESTRICTED")
  void socialSignupSkippingPreChecks() {
    String email = email();
    saveWithdrawal(email, phone(), databaseClock.now());
    String signupToken = jwtProvider.createSignupToken("KAKAO", kakaoId(), email);

    assertRejected(() -> socialSignupService.signup(signupToken, socialCommand(phone())), MemberErrorCode.REJOIN_RESTRICTED);
    assertThat(memberRepository.existsByEmail(email)).isFalse();
  }

  @Test
  @DisplayName("경계: 정확히 30일 전에 탈퇴했으면 1단계 · 2단계 · 카카오 · 최종 가입(로컬 · 소셜)을 모두 통과")
  void exactlyThirtyDaysPassesAllStages() {
    Instant now = fixNow();
    String email = email();
    String phone = phone();
    String socialEmail = email();
    String socialPhone = phone();
    saveWithdrawal(email, phone, now.minus(RejoinRestriction.PERIOD));
    saveWithdrawal(socialEmail, socialPhone, now.minus(RejoinRestriction.PERIOD));

    emailVerificationService.sendCode(email, EmailPurpose.SIGNUP);
    assertThat(signupService.isPhoneAvailable(phone)).isTrue();
    signup(email, phone);
    assertThat(memberRepository.existsByEmail(email)).isTrue();

    String kakaoId = kakaoId();
    SocialLoginResult result = socialLoginService.login(new SocialLoginCommand(Provider.KAKAO, kakaoId, socialEmail));
    assertThat(result).isInstanceOf(SocialLoginResult.NewMember.class);
    String signupToken = ((SocialLoginResult.NewMember) result).signupToken();
    socialSignupService.signup(signupToken, socialCommand(socialPhone));
    assertThat(memberRepository.existsByEmail(socialEmail)).isTrue();
  }

  @Test
  @DisplayName("경계: 30일에서 1초가 모자라면 1단계 · 2단계 · 카카오 · 최종 가입(로컬 · 소셜)이 모두 막힘")
  void oneSecondBeforeThirtyDaysBlocksAllStages() {
    Instant now = fixNow();
    String email = email();
    String phone = phone();
    saveWithdrawal(email, phone, now.minus(RejoinRestriction.PERIOD).plusSeconds(1));

    assertRejected(() -> emailVerificationService.sendCode(email, EmailPurpose.SIGNUP),
        MemberErrorCode.REJOIN_RESTRICTED_EMAIL);
    assertRejected(() -> signupService.isPhoneAvailable(phone), MemberErrorCode.REJOIN_RESTRICTED_PHONE);
    assertRejected(() -> socialLoginService.login(new SocialLoginCommand(Provider.KAKAO, kakaoId(), email)),
        MemberErrorCode.REJOIN_RESTRICTED_EMAIL);
    assertRejected(() -> signup(email, phone), MemberErrorCode.REJOIN_RESTRICTED);
    String signupToken = jwtProvider.createSignupToken("KAKAO", kakaoId(), email);
    assertRejected(() -> socialSignupService.signup(signupToken, socialCommand(phone)), MemberErrorCode.REJOIN_RESTRICTED);
    assertThat(memberRepository.existsByEmail(email)).isFalse();
    then(mailSender).shouldHaveNoInteractions();
  }

  // 지금 DB 시각을 읽어 그 값으로 고정한다 (판정 · 소셜 가입 발급 시각이 모두 이 값을 씀)
  private Instant fixNow() {
    Instant now = databaseClock.now();
    doReturn(now).when(databaseClock).now();
    return now;
  }

  private void signup(String email, String phone) {
    int n = SEQ.incrementAndGet();
    verificationStore.saveVerified(EmailPurpose.SIGNUP, email, Duration.ofMinutes(30));
    signupService.signup(new SignupCommand(email, "plant1234", "사전" + n, phone,
        "004", String.format("3%011d", n), "홍길동",
        "홍길동", "01099998888", "12345", "서울시 어딘가 1", null, null));
  }

  private SocialSignupCommand socialCommand(String phone) {
    int n = SEQ.incrementAndGet();
    return new SocialSignupCommand("사전소셜" + n, phone, "004", String.format("4%011d", n), "홍길동",
        "홍길동", "01099998888", "12345", "서울시 어딘가 1", null, null);
  }

  private void saveWithdrawal(String email, String phone, Instant withdrawnAt) {
    recordRepository.save(MemberWithdrawalRecord.create(memberHmac.emailHash(email), memberHmac.phoneHash(phone), withdrawnAt));
  }

  private static String email() {
    return "precheck-" + SEQ.incrementAndGet() + "@test.com";
  }

  private static String phone() {
    // 같은 PostgreSQL을 쓰는 다른 테스트(016 · 017 · 019)와 겹치지 않는 접두사
    return String.format("018%08d", SEQ.incrementAndGet());
  }

  private static String kakaoId() {
    return "precheck-kakao-" + SEQ.incrementAndGet();
  }

  private static void assertRejected(ThrowingCallable call, MemberErrorCode code) {
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

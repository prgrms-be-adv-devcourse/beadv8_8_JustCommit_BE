package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.security.JwtVerifier;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.common.testcontainers.SharedRedis;
import com.justcommit.backend.member.auth.domain.RevocationReason;
import com.justcommit.backend.member.auth.infrastructure.redis.RefreshTokenRedisStore;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

/**
 * 폐기와 같은 초에 발급한 Access (#546).
 * Gateway는 iat <= 끊은 시각인 Access를 거절하므로, 폐기 직후 같은 초에 정상 발급한 Access도 거절 대상이 된다.
 * 해결 전의 현재 동작을 발급 경로별로 기록한다 (경로를 고칠 때 그 경로의 기대를 뒤집는다).
 * 같은 초는 DatabaseClock을 끊은 시각의 0.5초 뒤로 고정해 만든다 (KakaoReloginIntegrationTest와 같은 방식).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class SameSecondAccessIssueIntegrationTest {

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final String PASSWORD = "plant1234";
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
  private LoginService loginService;

  @Autowired
  private TokenReissueService tokenReissueService;

  @Autowired
  private RefreshTokenRedisStore refreshTokenRedisStore;

  // Gateway와 같은 검증기로 iat(초)를 꺼냄
  @Autowired
  private JwtVerifier jwtVerifier;

  @Autowired
  private MemberRepository memberRepository;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private StringRedisTemplate redisTemplate;

  // 같은 초를 만들 때만 발급 시각을 고정하고, 그 전(폐기)에는 실제 DB 시계
  @MockitoSpyBean
  private DatabaseClock databaseClock;

  private final List<Long> memberIds = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    for (Long id : memberIds) {
      jdbcTemplate.update("delete from member_token_revocation where member_id = ?", id);
      jdbcTemplate.update("delete from member where id = ?", id);
    }
    try (var connection = redisTemplate.getRequiredConnectionFactory().getConnection()) {
      connection.serverCommands().flushAll();
    }
  }

  @Test
  @DisplayName("[S-A08 재현 · #546] 폐기와 같은 초에 로컬 로그인해 받은 Access는 iat = 끊은 시각이라 Gateway 규칙(iat <= 끊은 시각)상 거절 대상 — 현재 동작 기록")
  void sameSecondLoginAccessIsRejectable() {
    Member member = saveMember();
    long cutoff = revoke(member.getId());
    fixClockToSameSecond(cutoff);

    LoginResult result = loginService.login(new LoginCommand(member.getEmail(), PASSWORD));

    assertThat(issuedAtOf(result.accessToken())).isEqualTo(cutoff);
  }

  @Test
  @DisplayName("[S-A08 재현 · #546] 폐기와 같은 초에 로그인 → 재발급으로 받은 Access도 iat = 끊은 시각 — 현재 동작 기록")
  void sameSecondLoginThenReissueAccessIsRejectable() {
    Member member = saveMember();
    long cutoff = revoke(member.getId());
    fixClockToSameSecond(cutoff);
    String refreshToken = loginService.login(new LoginCommand(member.getEmail(), PASSWORD)).refreshToken();

    TokenReissueResult result = tokenReissueService.reissue(refreshToken);

    assertThat(issuedAtOf(result.accessToken())).isEqualTo(cutoff);
  }

  @Test
  @DisplayName("[S-A08 재현 · #546] 폐기 뒤 인증 버전의 Refresh로 같은 초에 재발급해 받은 Access는 iat = 끊은 시각 (로그인 경로와 무관) — 현재 동작 기록")
  void sameSecondReissueAccessIsRejectable() {
    Member member = saveMember();
    long cutoff = revoke(member.getId());
    // 폐기가 올린 인증 버전(1)으로 저장한 Refresh: 로그인 경로를 거치지 않고 재발급만 확인
    refreshTokenRedisStore.save("refresh-after-revocation", member.getId(), 1, Duration.ofDays(1));
    fixClockToSameSecond(cutoff);

    TokenReissueResult result = tokenReissueService.reissue("refresh-after-revocation");

    assertThat(issuedAtOf(result.accessToken())).isEqualTo(cutoff);
  }

  // 실제 폐기 처리로 Redis에 끊은 시각(초)을 남기고, Gateway TokenRevocationChecker와 같은 방식으로 읽음
  private long revoke(Long memberId) {
    revocationService.revoke(memberId, RevocationReason.PASSWORD_CHANGED, member -> { });
    return Long.parseLong(redisTemplate.opsForValue().get("auth:revoked-member:" + memberId));
  }

  // 시각에 따라 들쭉날쭉하지 않도록, 이후 발급 시각을 끊은 시각과 같은 초(0.5초 뒤)로 고정
  private void fixClockToSameSecond(long cutoff) {
    doReturn(Instant.ofEpochSecond(cutoff).plusMillis(500)).when(databaseClock).now();
  }

  private long issuedAtOf(String accessToken) {
    return jwtVerifier.verifyAccessToken(accessToken).issuedAt();
  }

  private Member saveMember() {
    int n = SEQ.incrementAndGet();
    Member member = memberRepository.save(Member.createLocal(
        "samesecond" + n + "@test.com", passwordEncoder.encode(PASSWORD), "같은초" + n, String.format("014%08d", n),
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
